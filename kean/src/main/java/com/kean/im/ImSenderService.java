package com.kean.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

/**
 * 把课安的业务消息「镜像投递」到 box-im <b>im-server</b> 的 Redis List 队列
 * （阶段 3 的投递侧，<b>只新增、不改既有链路</b>）。
 *
 * <h2>为什么 kean 能直接写队列（不需要部署 im-platform）</h2>
 * <p>box-im 的 im-server 是<b>自己拉 Redis List</b> 的：每个服务端实例启动时从
 * {@code im:max_server_id} 自增拿到自己的 {@code serverId}（{@code IMServerGroup.run}），
 * 然后每个消费者按 <b>基键 + ":" + serverId</b> 拉取
 * （{@code AbstractPullMessageTask.generateKey()} =
 * {@code String.join(":", super.generateKey(), IMServerGroup.serverId + "")}）。</p>
 *
 * <p>所以只要 kean 与 im-server <b>共用同一个 Redis 库</b>，
 * 并且写到<b>带 serverId 后缀</b>的队列键上，im-server 就能消费到。
 * 这与 {@link ImKickService#forceLogout} 写 {@code im:user:force_logout:{serverId}} 是同一套机制。</p>
 *
 * <h2>⚠️ 关键：队列键必须带 serverId，但基础后缀里没有 serverId</h2>
 * <p>{@code IMRedisKey.IM_MESSAGE_PRIVATE_QUEUE = "im:message:private"} 只是<b>基键</b>。
 * 真实可被消费的键是：</p>
 * <pre>
 * im:message:private:{serverId}
 * im:message:system:{serverId}
 * </pre>
 * <p>其中 {@code serverId} 从 <b>接收方</b>的在线槽位键读出
 * （{@code IMRedisKey.userServerIdKey(userId, terminal)} =
 * {@code im:user:server_id:{userId}:{terminal}}，值即该用户所在 im-server 的 serverId；
 * 键不存在代表该终端离线）。这与 im-client 的 {@code IMSender.pushPrivateMessage} 完全一致：</p>
 * <pre>
 * String queueKey = String.join(":", IMRedisKey.IM_MESSAGE_PRIVATE_QUEUE, serverId.toString());
 * redisMQTemplate.opsForList().rightPush(queueKey, recvInfo);
 * </pre>
 * <p><b>写基键（不带 serverId）是无效的</b> —— im-server 端的消费者永远只拉
 * {@code ...:1} / {@code ...:2} 这类带后缀的键，消息会永久留在队列里没人读。</p>
 *
 * <h2>消息体（无需 {@code @type}）</h2>
 * <p>队列元素的 Java 类型固定为 {@code IMRecvInfo}
 * （{@code PullPrivateMessageTask extends AbstractPullMessageTask<IMRecvInfo>}，
 * 消费端 {@code RedisMQPullTask} 用 {@code jsonObject.toJavaObject(type)} 反序列化），
 * 因此 kean 用 Jackson 写出的<b>同构 JSON</b> 能被 FastJson 正常读取，
 * <b>不需要</b> {@code @type} 字段（这一点已在 {@code FORCE_LOGOUT} 通道上核实并复用）。</p>
 *
 * <h2>私聊 {@code data} 是「box VO + kean 客户端字段」的并集</h2>
 * <p>交给 im-server 的 {@code IMRecvInfo.data} 由 kean 自己构造。im-server 对 {@code data}
 * <b>只透传不解析</b>，所以除了 box {@code PrivateMessageVO} 的字段外，这里还额外补了
 * kean 客户端认识的 {@code sessionId} / {@code senderId} / {@code msgType} / {@code createdAt}
 * —— 缺 {@code sessionId} 时客户端 {@code applyIncoming()} 会直接丢弃这条消息、
 * 气泡出不来（原因与取舍见 {@link #privateRecvInfoJson}）。</p>
 *
 * <h2>IM 未启用时零影响</h2>
 * <p>{@link #enabled()} 直接委托 {@link ImTokenService#enabled()} —— 也就是
 * {@code IM_JWT_SECRET} 未配置或不足 32 字节时为 {@code false}。
 * 此时所有公开方法<b>立刻返回</b>，不读 Redis、不写 Redis、不打 WARN（只有 DEBUG）。
 * 每个方法各自 try/catch，<b>任何异常都只 log.warn，绝不外抛</b>，
 * 因此调用方（{@code ChatServiceImpl} / {@code RealtimePublisher}）的业务事务不会受影响。</p>
 *
 * <h2>投递计数（监控，第 ① 项）</h2>
 * <p>本类额外维护四个无锁计数器（{@link java.util.concurrent.atomic.LongAdder}），
 * 通过 {@link #snapshot()} 只读暴露给巡检任务与健康端点，用途见 {@link ImDeliveryStats}：
 * {@code attempts} / {@code pushed} / {@code failed} / {@code skipped}。</p>
 *
 * <p><b>刻意不影响发送路径</b>：</p>
 * <ul>
 *   <li>{@code LongAdder.increment()} / {@code add()} 是 CAS 无锁操作，
 *       <b>不抛异常</b>（溢出也只是静默回绕，不会中断投递），因此计数逻辑不可能成为
 *       投递失败的新来源；</li>
 *   <li>计数调用点全部在 {@code try}/{@code catch} 之外或 catch 块的第一行，
 *       即使它们在 catch 块里也不会掩盖原始异常（原异常此刻已被捕获并即将 log.warn）。</li>
 * </ul>
 *
 * <p>⚠️ 计数<b>不改变</b>本类「吞异常」的既有行为：所有公开方法仍然
 * {@code return 0} + {@code log.warn}，绝不外抛。</p>
 */
@Service
public class ImSenderService {

    private static final Logger log = LoggerFactory.getLogger(ImSenderService.class);

    /**
     * box-im {@code IMRedisKey.IM_MESSAGE_PRIVATE_QUEUE = "im:message:private"}。
     * 真实队列键是 {@code im:message:private:{serverId}}，见类注释。
     */
    private static final String PRIVATE_QUEUE_PREFIX = "im:message:private";

    /**
     * box-im {@code IMRedisKey.IM_MESSAGE_SYSTEM_QUEUE = "im:message:system"}。
     * 真实队列键是 {@code im:message:system:{serverId}}，见类注释。
     */
    private static final String SYSTEM_QUEUE_PREFIX = "im:message:system";

    /**
     * box-im {@code IMRedisKey.IM_USER_SERVER_ID = "im:user:server_id"}。
     * 完整键 {@code im:user:server_id:{userId}:{terminal}}（与 {@link ImKickService} 同一套拼法）。
     */
    private static final String USER_SERVER_ID_PREFIX = "im:user:server_id";

    /** box-im {@code IMCmdType.PRIVATE_MESSAGE.code() = 3}。 */
    private static final int CMD_PRIVATE_MESSAGE = 3;

    /** box-im {@code IMCmdType.SYSTEM_MESSAGE.code() = 5}。 */
    private static final int CMD_SYSTEM_MESSAGE = 5;

    /**
     * box-im {@code IMTerminalType}：WEB=0 / APP=1 / PC=2。
     * 必须三个都试 —— {@code userServerIdKey} 是按 (userId, terminal) 分槽位的，
     * kean 侧无法知道接收方当前挂在哪个终端上（与 {@link ImKickService} 同样的取舍）。
     */
    private static final int[] TERMINALS = {0, 1, 2};

    /** box-im {@code MessageType.TEXT.code() = 0}。 */
    private static final int BOX_TYPE_TEXT = 0;

    /** box-im {@code MessageType.IMAGE.code() = 1}。 */
    private static final int BOX_TYPE_IMAGE = 1;

    /** 写进 {@code IMRecvInfo.serviceName}；im-server 只用它拼「发送结果」队列键，我们不需要回执。 */
    private static final String SERVICE_NAME = "kean";

    /**
     * 广播兜底时最多枚举多少个 serverId（见 {@link #scanKnownServerIds()}）。
     *
     * <p>纯防御：{@code im:max_server_id} 只增不减，若这个计数器被异常抬高（或换了环境复用同一个 Redis），
     * 不加封顶就会对成千上万个不存在的 serverId 各 push 一条消息。取 64 是「远超正常实例数、
     * 又不至于失控」的经验上界；达到上界时下面的循环会打出 WARN，便于发现异常。</p>
     *
     * <p><b>注意</b>：kean 侧正常业务只用 {@link #sendPrivate} 与定向
     * {@link #sendSystem(Long, Object)}，广播分支只服务于 {@link #sendSystem(java.util.List, Object)}
     * 传 {@code null} 的情形。</p>
     */
    private static final long MAX_BROADCAST_SERVER_ID = 64L;

    private final ImTokenService imTokenService;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /**
     * 是否开启镜像投递。
     *
     * <p><b>默认跟随 {@code IM_JWT_SECRET} 是否配置</b>（即 {@code IM_JWT_SECRET} 配好就默认开投递），
     * 可用环境变量 {@code KEAN_IM_MIRROR_ENABLED=false}（Spring relaxed binding →
     * {@code kean.im.mirror-enabled}）单独关掉「只投递、不连」的中间态。
     * <b>不是</b>「关掉它就能让 IM 失效」的总开关 —— 总开关是 {@code IM_JWT_SECRET} 本身。</p>
     */
    private final boolean mirrorEnabled;

    /**
     * 投递计数（第 ① 项监控）。四个计数器分别对应
     * {@link ImDeliveryStats} 的四个字段，语义与粒度见该类注释。
     *
     * <p>选 {@link LongAdder} 而不是 {@code AtomicLong}：IM 投递是<b>高并发写、极低频读</b>
     * （每条消息都会写、巡检 60 秒读一次），{@code LongAdder} 在竞争下的写开销更低；
     * {@code sum()} 不是原子快照，但计数只用于「本轮增量是否超阈值」这种量级判断，
     * 少量误差无害。</p>
     */
    private final LongAdder deliveryAttempts = new LongAdder();

    private final LongAdder deliveryPushed = new LongAdder();

    private final LongAdder deliveryFailed = new LongAdder();

    private final LongAdder deliverySkipped = new LongAdder();

    public ImSenderService(ImTokenService imTokenService,
                           StringRedisTemplate redis,
                           ObjectMapper objectMapper,
                           @Value("${kean.im.mirror-enabled:true}") boolean mirrorEnabled) {
        this.imTokenService = imTokenService;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.mirrorEnabled = mirrorEnabled;
        // 启动即把「镜像投递开没开、密钥就绪没就绪」打出来。
        // 目的：运维能一眼看出配置到底有没有被 Spring 读到 —— 只看 /proc/<pid>/environ 只能证明
        // systemd 注入了环境变量，不能证明 @Value 解析成功（键名拼错时会静默沿用默认值 true）。
        log.info("[IM 镜像投递] mirrorEnabled={}（配置项 kean.im.mirror-enabled / 环境变量 KEAN_IM_MIRROR_ENABLED），"
                        + "tokenReady={}（IM_JWT_SECRET 是否已配置且 ≥32 字节）→ 实际生效 enabled={}",
                mirrorEnabled, imTokenService.enabled(), mirrorEnabled && imTokenService.enabled());
    }

    /**
     * IM 通道就绪 且 镜像投递未被显式关闭。
     *
     * <p>{@code IM_JWT_SECRET} 未配置 → 恒 {@code false}（与 {@link ImKickService} 同一判据）。</p>
     */
    public boolean enabled() {
        return mirrorEnabled && imTokenService.enabled();
    }

    /**
     * 投递计数的<b>只读快照</b>（第 ① 项监控）。
     *
     * <p>无锁、无副作用、不读 Redis：任何时刻调用都安全，返回的是某个瞬间的近似值
     * （{@link LongAdder#sum()} 不保证跨字段原子一致，用于阈值判断足够）。</p>
     *
     * @return 累计计数快照，含义与粒度见 {@link ImDeliveryStats}
     */
    public ImDeliveryStats snapshot() {
        return new ImDeliveryStats(
                deliveryAttempts.sum(),
                deliveryPushed.sum(),
                deliveryFailed.sum(),
                deliverySkipped.sum());
    }

    /**
     * 投递一条<b>私聊消息</b>到 {@code im:message:private:{serverId}}。
     *
     * @param recvId  接收方用户 id（= box 的 {@code PrivateMessageVO.recvId}）
     * @param sendId  发送方用户 id（= box 的 {@code PrivateMessageVO.sendId}）
     * @param sessionId kean 的会话 id（{@code chat_session.id}）——<b>必须有</b>，
     *                见 {@link #privateRecvInfoJson} 里 {@code data.sessionId} 的说明
     * @param msgType kean 的消息类型（{@code "TEXT"} / {@code "IMAGE"}），会映射成 box 的数字类型
     * @param content 消息内容（图片时是对象存储 objectKey，与 kean 现有 {@code chat_message.content} 同义）
     * @param localId kean 的幂等 id（{@code chat_message.local_id}），可为 {@code null}
     * @param seqNo   kean 的会话内序号（{@code chat_message.seq_no}），可为 {@code null}
     * @param createdAt 该条消息的入库创建时间（{@code chat_message.created_at}）。
     *                  <b>必须是落库后的真实值</b>（{@code AuditMetaObjectHandler} 填充的那个），
     *                  不要在这里重新取 {@code now()} —— 镜像要与 HTTP 路径返回的
     *                  {@code ChatMessageVO.createdAt} 完全一致，客户端会拿它算已读时间点。
     *                  {@code null} 时回落到当前时间。
     * @return 实际投递的队列个数（<b>按 serverId 去重</b>）；IM 未启用 / 接收方离线时为 {@code 0}
     */
    public int sendPrivate(Long recvId, Long sendId, Long sessionId,
                           String msgType, String content, String localId, Long seqNo,
                           LocalDateTime createdAt) {
        if (!enabled()) {
            // 计数点 ①：IM 未启用导致的 no-op（唯一计入 skipped 的分支）。
            deliverySkipped.increment();
            log.debug("[IM 镜像投递跳过] IM 通道未就绪，私聊消息不投递到 im-server（kean 现有链路不受影响）");
            return 0;
        }
        if (recvId == null || sendId == null || !StringUtils.hasText(content)) {
            // 参数非法：既不是投递尝试也不是故障，刻意不计数（否则脏调用会把失败率打起来）。
            return 0;
        }
        // 计数点 ②：真正进入投递逻辑的「消息条数」。放在参数校验之后、try 之前。
        deliveryAttempts.increment();
        try {
            Set<String> serverIds = readServerIds(recvId);
            if (serverIds.isEmpty()) {
                log.debug("[IM 镜像投递] 接收方 userId={} 在 im-server 上无在线连接，跳过私聊投递", recvId);
                return 0;
            }
            String body = privateRecvInfoJson(recvId, sendId, sessionId, msgType, content, localId, seqNo,
                    createdAt == null ? LocalDateTime.now() : createdAt);
            return push(serverIds, PRIVATE_QUEUE_PREFIX, body,
                    String.format(Locale.ROOT, "私聊消息 recvId=%d sendId=%d sessionId=%s seqNo=%s",
                            recvId, sendId, sessionId, seqNo));
        } catch (Exception ex) {
            // 计数点 ③：一次投递（消息级）失败。注意逐队列写入失败在 push() 里另计。
            deliveryFailed.increment();
            log.warn("[IM 镜像投递] 私聊消息投递失败（不影响 kean 现有业务），recvId={}, sendId={}：{}",
                    recvId, sendId, ex.getMessage());
            return 0;
        }
    }

    /**
     * 投递一条<b>系统消息</b>到 {@code im:message:system:{serverId}}。
     *
     * @param recvIds 接收方用户 id 列表；<b>{@code null} 或空列表表示广播</b>
     *                （box 的 {@code IMSystemMessage.recvIds} 注释即「为空表示向所有在线用户广播」）。
     *                单个用户请传 {@code List.of(userId)} —— 本服务<b>不</b>把单个 id 当成广播，
     *                避免「只是想通知一个人」被静默放大成全站推送。
     * @param data    消息体（会被放进 {@code IMRecvInfo.data}，客户端在 {@code {cmd:5,data:...}} 里读到）
     * @return 实际投递的队列个数（按 serverId 去重）
     */
    public int sendSystem(List<Long> recvIds, Object data) {
        if (!enabled()) {
            // 计数点 ①（系统消息侧）：与 sendPrivate 同一个计数器。
            deliverySkipped.increment();
            log.debug("[IM 镜像投递跳过] IM 通道未就绪，系统消息不投递到 im-server（kean 现有链路不受影响）");
            return 0;
        }
        if (data == null) {
            return 0;
        }
        // 计数点 ②（系统消息侧）。
        deliveryAttempts.increment();
        try {
            boolean broadcast = recvIds == null || recvIds.isEmpty();
            // 广播：box 的广播约定就是 receivers 为空列表（im-server 会把「未找到 channel」的接收者写进结果队列，
            // 我们 sendResult=false 所以不会产生回执），这里显式传空列表以保持与 box 语义一致。
            List<Map<String, Object>> receivers = new ArrayList<>();
            Set<String> serverIds = new LinkedHashSet<>();
            if (!broadcast) {
                for (Long userId : recvIds) {
                    if (userId == null) {
                        continue;
                    }
                    for (int terminal : TERMINALS) {
                        String serverId = readServerId(userId, terminal);
                        if (serverId == null) {
                            continue;
                        }
                        serverIds.add(serverId);
                        Map<String, Object> receiver = new LinkedHashMap<>();
                        receiver.put("id", userId);
                        receiver.put("terminal", terminal);
                        receivers.add(receiver);
                    }
                }
            }
            if (broadcast && serverIds.isEmpty()) {
                // 广播模式下我们不知道任何在线的 serverId：这时只能投递到「本 Redis 里已知的」im-server 槽位。
                // 没有已知槽位说明 im-server 从未有用户上线（或未部署），投递也没有消费方，直接跳过并留日志。
                serverIds.addAll(scanKnownServerIds());
                if (serverIds.isEmpty()) {
                    log.debug("[IM 镜像投递] 尚无 im-server 槽位，广播消息跳过");
                    return 0;
                }
            }
            if (!broadcast && serverIds.isEmpty()) {
                log.debug("[IM 镜像投递] 接收方 {} 在 im-server 上均无在线连接，跳过系统消息投递", recvIds);
                return 0;
            }
            String body = systemRecvInfoJson(receivers, data);
            return push(serverIds, SYSTEM_QUEUE_PREFIX, body,
                    String.format(Locale.ROOT, "系统消息 receivers=%d broadcast=%s", receivers.size(), broadcast));
        } catch (Exception ex) {
            // 计数点 ③（系统消息侧）。
            deliveryFailed.increment();
            log.warn("[IM 镜像投递] 系统消息投递失败（不影响 kean 现有业务）：{}", ex.getMessage());
            return 0;
        }
    }

    /** 便捷重载：只给一个用户投系统消息（等价于 {@code sendSystem(List.of(userId), data)}）。 */
    public int sendSystem(Long recvId, Object data) {
        if (recvId == null) {
            return 0;
        }
        List<Long> one = new ArrayList<>(1);
        one.add(recvId);
        return sendSystem(one, data);
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 按 serverId 去重后逐个 {@code rightPush}。
     *
     * <p><b>去重的原因</b>：同一 im-server 上可能同时挂着该用户的多个终端，
     * 但队列是「按 serverId 一个」而不是「按终端一个」，重复写会让同一条消息被推送多次
     * （im-server 侧 {@code PrivateMessageProcessor} 会遍历 receivers 逐个推送）。
     * 因为 {@code receivers} 里已经列全了终端，一个 serverId 只需要一条 {@code IMRecvInfo}。</p>
     */
    private int push(Set<String> serverIds, String queuePrefix, String body, String desc) {
        int pushed = 0;
        for (String serverId : serverIds) {
            try {
                redis.opsForList().rightPush(queuePrefix + ":" + serverId, body);
                pushed++;
            } catch (Exception ex) {
                // 计数点 ④：单个队列写入失败（这里是 Redis 抖动/超时最直接的观测点：
                // push 内部吞掉异常，若不计数，业务层与巡检都看不到）。
                deliveryFailed.increment();
                log.warn("[IM 镜像投递] 写入队列 {}{} 失败，{}：{}", queuePrefix, ":" + serverId, desc, ex.getMessage());
            }
        }
        // 计数点 ②（写入侧）：按「实际写成功的队列个数」累加，与返回值同源。
        if (pushed > 0) {
            deliveryPushed.add(pushed);
            log.info("[IM 镜像投递] 已投递 {} 个 im-server 队列（{}），{}", pushed, queuePrefix + ":*", desc);
        }
        return pushed;
    }

    /** 读某用户在三个终端上的 im-server 槽位，返回去重后的 serverId 集合（可能为空）。 */
    private Set<String> readServerIds(Long userId) {
        Set<String> serverIds = new LinkedHashSet<>();
        for (int terminal : TERMINALS) {
            String serverId = readServerId(userId, terminal);
            if (serverId != null) {
                serverIds.add(serverId);
            }
        }
        return serverIds;
    }

    /**
     * 读 {@code im:user:server_id:{userId}:{terminal}}。
     *
     * <p>键不存在（离线）或值不是数字（脏数据）时返回 {@code null}。
     * 用 {@code get} + 判空而不是 {@code hasKey} 再读，避免两次往返。</p>
     */
    private String readServerId(Long userId, int terminal) {
        try {
            String value = redis.opsForValue().get(USER_SERVER_ID_PREFIX + ":{" + userId + "}:" + terminal);
            if (!StringUtils.hasText(value)) {
                return null;
            }
            String trimmed = value.trim();
            // serverId 是 IMServerGroup 里 increment 出来的正整数，用 parse 做一次防御性校验，
            // 避免把脏值拼进队列键（拼出错键比不投递更难排查）。
            long parsed = Long.parseLong(trimmed);
            return parsed > 0 ? String.valueOf(parsed) : null;
        } catch (NumberFormatException ex) {
            log.warn("[IM 镜像投递] im:user:server_id 槽位值不是数字，userId={}, terminal={}，已忽略", userId, terminal);
            return null;
        }
    }

    /**
     * 广播模式下的 serverId 兜底来源。
     *
     * <p>本方法<b>不扫描 keyspace</b>（{@code KEYS} 在大库上会阻塞 Redis，线上禁用）。
     * 改为读 {@code im:max_server_id}（im-server 每次启动自增的计数器，{@code IMServerGroup.run}），
     * 取其 1..N —— 这就是「曾经启动过的 im-server 编号上界」。</p>
     *
     * <p><b>⚠️ 已知取舍</b>：counter 只增不减，服务缩容后会对已经不存在的 serverId
     * 也投递一份（队列无人消费，仅占内存）。这是为了「广播也能投出去」而接受的代价；
     * 需要精确广播时应让 im-platform 承担（它是 box 原本的广播方）。
     * 生产上建议 kean 侧只用 {@link #sendPrivate} + 定向 {@link #sendSystem(Long, Object)}。</p>
     */
    private Set<String> scanKnownServerIds() {
        Set<String> serverIds = new LinkedHashSet<>();
        try {
            String max = redis.opsForValue().get("im:max_server_id");
            if (!StringUtils.hasText(max)) {
                return serverIds;
            }
            long upper = Long.parseLong(max.trim());
            if (upper > MAX_BROADCAST_SERVER_ID) {
                log.warn("[IM 镜像投递] im:max_server_id={} 超过封顶 {}，广播只覆盖前 {} 个 serverId；"
                        + "若这是异常值请检查该计数器", upper, MAX_BROADCAST_SERVER_ID, MAX_BROADCAST_SERVER_ID);
            }
            for (long id = 1; id <= upper && id <= MAX_BROADCAST_SERVER_ID; id++) {
                serverIds.add(String.valueOf(id));
            }
        } catch (Exception ex) {
            log.debug("[IM 镜像投递] 读取 im:max_server_id 失败，广播跳过：{}", ex.getMessage());
        }
        return serverIds;
    }

    /**
     * {@code IMRecvInfo} 的 JSON（cmd=PRIVATE_MESSAGE(3)），data 为
     * {@code PrivateMessageVO} <b>同构对象</b>。
     *
     * <h3>为什么 data 里要额外塞 {@code sessionId} / {@code msgType} / {@code createdAt}</h3>
     * <p>box 的 {@code PrivateMessageVO} <b>没有</b> {@code sessionId} 字段（box 的私聊会话靠
     * {@code sendId} 反推），而 kean 的客户端 {@code chat.vue} 在
     * {@code applyIncoming()} 里的第一句判断就是
     * {@code Number(payload.sessionId || 0) !== sessionId.value → 直接 return}。
     * 所以镜像消息<b>只要缺 sessionId 就一定进不了气泡</b>，最多被别处当成未读去刷角标。</p>
     *
     * <p>这份 {@code data} 是 <b>kean 自己构造</b>的，而 box 的 im-server 对
     * {@code IMRecvInfo.data} <b>只透传不解析</b>（它按 {@code cmd} 分发、把 data 原样
     * 塞进下行帧 {@code {cmd:3,data:...}}），所以多出来的字段对 box 侧完全无害；
     * 对 kean 客户端则正好补齐它认识的 {@code ChatMessageItem} 形状
     * （{@code sessionId}/{@code senderId}/{@code msgType}/{@code seqNo}/{@code localId}/
     * {@code status}/{@code createdAt}）。字段名一律小驼峰，与 box VO 风格一致。</p>
     *
     * <h3>时间字段刻意用 ISO-8601 字符串，不用 epoch 毫秒</h3>
     * <p>{@code ChatMessageItem.createdAt} 声明的是 <b>string</b>，客户端
     * {@code chat.vue} 用 {@code Date.parse(String(payload.createdAt))} 去算已读时间点。
     * {@code chat_message.created_at} 是 {@code LocalDateTime}，HTTP 路径那边由 Jackson
     * 默认序列化成 ISO-8601，所以这里也传 {@code LocalDateTime}（Jackson 写出同样的字符串），
     * <b>两条通道给客户端的 {@code createdAt} 是同一个格式</b>。
     * 写成 {@code Instant.now().toEpochMilli()} 的数字会让 {@code Date.parse} 拿到
     * {@code "1767..."} 而得到 {@code NaN}。</p>
     */
    private String privateRecvInfoJson(Long recvId, Long sendId, Long sessionId, String msgType, String content,
                                       String localId, Long seqNo, LocalDateTime createdAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        // 字段名逐一对齐 box 的 com.bx.implatform.vo.PrivateMessageVO（Lombok @Data → Jackson 小驼峰）。
        // id 缺席是刻意的：kean 的消息主键与 im_platform.im_private_message 不是同一套编号，
        // 硬塞 kean 的 id 会让客户端把它当成 box 的消息 id 去调 box 的历史/已读接口。
        data.put("localId", localId == null ? "" : localId);
        data.put("seqNo", seqNo == null ? 0L : seqNo);
        data.put("sendId", sendId);
        data.put("recvId", recvId);
        data.put("content", content);
        data.put("type", boxMessageType(msgType));
        // box 的 MessageStatus：0 未读 / 1 已发送 / 2 撤回 / 3 已读。
        // kean 落库时新消息即 1（已发送），这里保持同义。
        data.put("status", 1);
        // 这里为了与下面的 createdAt 格式统一（客户端只认 ISO-8601 字符串），没有沿用
        // box 的 epoch 毫秒。im-server 不解析该字段，所以不影响投递。
        data.put("sendTime", createdAt);
        data.put("deleted", false);

        // —— 以下四个字段 box 的 VO 里没有，是给 kean 自己的客户端映射用的（见方法注释）——
        // sessionId：客户端 applyIncoming 的准入条件，缺了气泡就出不来。
        //   兜 0 而不是 null：客户端的判断是 Number(x || 0) !== 当前会话，0 一定不匹配，
        //   行为等价于「不显示」，但不会让 Jackson 写出 null 干扰其他字段。
        data.put("sessionId", sessionId == null ? 0L : sessionId);
        // senderId：box 那边叫 sendId，kean 的 ChatMessageItem 叫 senderId，两个都给，
        //   这样同一个 data 既能被 box 客户端读，也能被 kean 客户端直接当成一条消息。
        data.put("senderId", sendId);
        // msgType：kean 的字符串类型（TEXT / IMAGE），与上面的数字型 type 并存、互不冲突。
        data.put("msgType", normalizedMsgType(msgType));
        // createdAt：kean 客户端用它算已读时间点（Date.parse(createdAt)），
        //   与落库值同源，只换了 kean 认的字段名。
        data.put("createdAt", createdAt);

        Map<String, Object> recvInfo = new LinkedHashMap<>();
        recvInfo.put("cmd", CMD_PRIVATE_MESSAGE);
        recvInfo.put("sender", sender(sendId));
        recvInfo.put("receivers", receivers(recvId));
        recvInfo.put("serviceName", SERVICE_NAME);
        // false：不生成「发送结果」回执。box 把回执写到 im:result:private:{serviceName}，
        // 而那个队列的消费者在 im-platform 里；kean 没有消费者，设 true 只会让 Redis 无界增长。
        recvInfo.put("sendResult", false);
        recvInfo.put("data", data);
        return toJson(recvInfo);
    }

    /** {@code IMRecvInfo} 的 JSON（cmd=SYSTEM_MESSAGE(5)），sender 留空（系统消息无发送方）。 */
    private String systemRecvInfoJson(List<Map<String, Object>> receivers, Object data) {
        Map<String, Object> recvInfo = new LinkedHashMap<>();
        recvInfo.put("cmd", CMD_SYSTEM_MESSAGE);
        recvInfo.put("sender", null);
        recvInfo.put("receivers", receivers);
        recvInfo.put("serviceName", SERVICE_NAME);
        recvInfo.put("sendResult", false);
        recvInfo.put("data", data);
        return toJson(recvInfo);
    }

    /** {@code IMUserInfo} 同构对象（{@code id} + {@code terminal}），发送方的 terminal 固定 APP。 */
    private Map<String, Object> sender(Long sendId) {
        Map<String, Object> sender = new LinkedHashMap<>();
        sender.put("id", sendId);
        sender.put("terminal", 1);
        return sender;
    }

    /** 接收方 receivers：三个终端都列上，由 im-server 自己判断哪个终端真的在线并推送。 */
    private List<Map<String, Object>> receivers(Long recvId) {
        List<Map<String, Object>> list = new ArrayList<>(TERMINALS.length);
        for (int terminal : TERMINALS) {
            Map<String, Object> receiver = new LinkedHashMap<>();
            receiver.put("id", recvId);
            receiver.put("terminal", terminal);
            list.add(receiver);
        }
        return list;
    }

    /**
     * kean 的字符串消息类型 → box {@code MessageType} 数字码。
     *
     * <p>kean 只允许 {@code TEXT} / {@code IMAGE}（见 {@code ChatServiceImpl.send}），
     * 未知值一律按 {@code TEXT(0)} 处理，不抛异常 —— 投递是「尽力而为」的附加动作。</p>
     */
    private static int boxMessageType(String msgType) {
        return "IMAGE".equals(normalizedMsgType(msgType)) ? BOX_TYPE_IMAGE : BOX_TYPE_TEXT;
    }

    /**
     * 归一化 kean 的消息类型：去空白、转大写，非法值回落到 {@code TEXT}。
     *
     * <p>镜像 {@code data} 里的 {@code msgType} 用的是这个结果（而不是调用方传进来的原始串），
     * 保证客户端拿到的永远是 {@code TEXT} / {@code IMAGE} 两个值之一 ——
     * 客户端是按等值比较（{@code item.msgType === "IMAGE"}）决定渲染气泡还是图片的，
     * 传个 {@code "image "} 过去会被渲染成纯文本。</p>
     */
    private static String normalizedMsgType(String msgType) {
        return "IMAGE".equalsIgnoreCase(msgType == null ? "" : msgType.trim()) ? "IMAGE" : "TEXT";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("IM 队列消息序列化失败", ex);
        }
    }
}
