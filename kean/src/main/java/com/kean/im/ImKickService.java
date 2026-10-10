package com.kean.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 封禁 / 解封时，让 <b>im-server（Netty 8878，WS 路径 {@code /im}）</b> 那边也生效（阶段 3 的准备项之二）。
 *
 * <h2>要解决的问题</h2>
 * <p>kean 封禁一个用户时会做四步联动（revoke JWT、写封禁位点、推公告、通知在线态），
 * 但<b>这些都管不到已经建立到 im-server 的长连接</b>：</p>
 * <ul>
 *   <li>{@code TokenRevokeService} / {@code TokenBlacklistService} 的黑名单只有 kean 自己的
 *       {@code JwtAuthFilter} 会读，im-server 用的是它自己那份 {@code jwt.accessToken.secret}
 *       独立验签，<b>不认识 kean 的黑名单</b>；</li>
 *   <li>{@code ChatSessionHub} 是课安自己的 WebSocket 通道，和 im-server 的 8878 不是一条连接。</li>
 * </ul>
 * <p>结果：被封用户不解封期间仍然能通过 im-server 收发消息。</p>
 *
 * <h2>本类做什么（两个动作，全部「尽力而为」）</h2>
 * <ol>
 *   <li>{@link #deny(Long, String)} 写一个 <b>kean 持有</b>的封禁标记键
 *       {@value #BANNED_KEY_PREFIX}{@code {userId}}（<b>无 TTL，永久直到解封</b>）。
 *       这是「封禁状态的持久位置」在 kean 侧的落点 —— 注意 box-im 自带的
 *       {@code im_user.is_banned} 是 im-platform 自己的库表（{@code docs/sql/im-platform.sql}）；
 *       ⚠️ 它的 {@code id} <b>就是</b> kean 的 {@code sys_user.id}（同 id 复用，见
 *       {@code docs/ops/im-platform-migration.md} §1.5 铁律 L1 与 {@code com.kean.im.ImShadowUserService}），
 *       所以两张表是「同 id 对齐」而不是「两套编号」；不过<b>封禁态的权威仍然是
 *       「kean 的库 + 这枚 Redis 键」</b>，{@code im_user.is_banned} 只是镜像（投影见阶段 B/D）；</li>
 *   <li>{@link #forceLogout(Long, String)} 按 box-im 的 {@code FORCE_LOGOUT(2)} 通道
 *       推一条强制下线指令，踢掉该用户在各个终端上的<b>在线长连接</b>。</li>
 * </ol>
 *
 * <p><b>刻意不写 {@code im:user:state}</b>（曾经写过，已删除，原因见
 * {@link #USER_STATE_PREFIX} 上的注释）：那枚键是 im-platform 的 <b>WebRTC 忙线标志</b>，
 * 与「在线 / 封禁」没有任何关系，写它只会污染通话状态。</p>
 *
 * <h2>✅ 事实（已用官方 master 4.0.0 源码 + 实测连接双重确认）</h2>
 * <p><b>im-server 上游自带「拒绝封禁用户」的逻辑，kean 不需要给它打任何补丁。</b></p>
 * <ul>
 *   <li>{@code IMRedisKey}（im-common，{@code :62}）确有 {@code IM_USER_DENIED = "im:user:denied"}；
 *       上游 im-platform 的 {@code UserBannedConsumerTask:38} 封禁时写它、{@code UserServiceImpl:70,105} 解封/登录时删它、
 *       {@code AuthInterceptor:51} 读它；</li>
 *   <li>{@code LoginProcessor.process:51}（im-server）在验签之后立刻就查这枚键：
 *       {@code if (Boolean.TRUE.equals(redisMQTemplate.hasKey(StrUtil.join(":", IMRedisKey.IM_USER_DENIED, userId)))) { close(); return; }}
 *       → <b>即「阻止被封用户重连」由上游负责，kean 只要写对键名</b>；</li>
 *   <li>{@code PullForceLogoutTask:16}（im-server，{@code @RedisMQListener(queue = "im:user:force_logout")}）
 *       消费踢线指令 → <b>「踢掉已有连接」也只需要 im-server，不需要 im-platform</b>。</li>
 * </ul>
 * <p><b>实测证据</b>（本项目在真实服务器上跑过）：① 无拒绝键时 WS 握手 101 且登录成功（收到 {@code {"cmd":0}}）；
 * ② 连接中向 {@code im:user:force_logout:{serverId}} 投递一条，客户端<b>收到 {@code {"cmd":2}}</b>；
 * ③ 写入 {@code im:user:denied:{userId}} 后重连，握手仍 101 但发出登录帧后<b>连接被立即关闭</b>。</p>
 * <p>⚠️ 历史遗留：本项目早期基于<b>较旧的 box-im 镜像仓库</b>调研，曾错误结论「im-server 没有封禁校验、
 * 必须自己打补丁」，并因此一度使用自造键 {@code kean:im:banned:{userId}} —— 那个键 im-server 完全不认。
 * 现已改为上游键名，{@code docs/ops/im-server-patch.md} 也已改为「无需打补丁，只需对齐键名与密钥」。
 * （下方这一段原来的错误描述保留在此仅为记录教训，不要按它行事。）</p>
 *
 * <p>所以本类的效果是：</p>
 * <ul>
 *   <li>{@link #forceLogout(Long, String)}：<b>只要 im-server 在跑</b>（{@code PullForceLogoutTask}
 *       就在 im-server 进程里，是 {@code @RedisMQListener} 消费者）就能真正踢掉已建立的连接 ——
 *       不需要 im-platform ✓（此处此前写反过，已按源码更正）；</li>
 *   <li>封禁标记键：im-server <b>会读它</b>（{@code LoginProcessor} 用 {@code hasKey} 拒绝重连，
 *       已实测），因此它<b>具备「拒绝被封用户重连」的强制力</b>；部署 im-platform 之后，
 *       它的 {@code AuthInterceptor} 也会读同一枚键（并且看<b>值</b>：{@code Integer}，
 *       见 {@link #markBanned}）—— 所以本类的值类型必须与 upstream 对齐。</li>
 * </ul>
 *
 * <h2>IM 未启用时零影响</h2>
 * <p>三个公开方法都用 {@link #active()} 开头做短路：{@code IM_JWT_SECRET} 未配置或长度不合格时
 * （{@link ImTokenService#enabled()} 为 {@code false}），一律 no-op，只在 DEBUG/WARN 留一行日志，
 * <b>不代表成功也不写任何 Redis 键</b>。调用方（{@code AccountBanServiceImpl}）另外用
 * try/catch 包住，所以本类即使抛异常也不会影响封禁本身。</p>
 */
@Service
public class ImKickService {

    private static final Logger log = LoggerFactory.getLogger(ImKickService.class);

    /**
     * box-im {@code IMRedisKey.IM_USER_SERVER_ID = "im:user:server_id"}。
     *
     * <p>{@code userServerIdKey()} 的拼法是
     * {@code String.join(":", IM_USER_SERVER_ID, "{" + userId + "}", terminal.toString())}，
     * 即最终键为 {@code im:user:server_id:{userId}:{terminal}}（Redis Cluster 用的 hash tag）。
     * 这里是<b>逐字节照抄</b>，不要「顺手统一」成别的前缀。</p>
     */
    private static final String USER_SERVER_ID_PREFIX = "im:user:server_id";

    /**
     * box-im {@code IMRedisKey.IM_USER_FORCE_LOGOUT_QUEUE = "im:user:force_logout"}。
     *
     * <p>真实队列键是 {@code im:user:force_logout:{serverId}}（im-server 的
     * {@code LoginProcessor} 里用 {@code StrUtil.join(":", IM_USER_FORCE_LOGOUT_QUEUE, serverId)}
     * 拼出来），所以这里只存前缀。</p>
     */
    private static final String FORCE_LOGOUT_QUEUE_PREFIX = "im:user:force_logout";

    /**
     * ⚠️ <b>本类刻意不再写 {@code im:user:state}</b>。下面这段事实是删除写入的依据，
     * 改动前务必读完 —— 这枚键与「在线状态」无关。
     *
     * <h3>核对结论（box-im master，2026-02）</h3>
     * <ul>
     *   <li>定义处：im-platform {@code com.bx.implatform.contant.RedisKey.IM_USER_STATE}，
     *       其注释是「<b>用户状态 无值:空闲 1:正在忙</b>」；</li>
     *   <li>唯一读写方：{@code com.bx.implatform.util.UserStateUtils}
     *       （{@code setBusy}/{@code setFree}/{@code expire}/{@code isBusy}）。
     *       它用 {@code StrUtil.join(":", RedisKey.IM_USER_STATE, userId)} 拼键，
     *       即真实键是 <b>{@code im:user:state:{userId}}</b>，值恒为 {@code 1}，
     *       <b>TTL 30 秒</b>（忙线靠过期自动释放）；</li>
     *   <li>唯一读取方：{@code WebrtcPrivateServiceImpl.call()} 里的
     *       {@code userStateUtils.isBusy(uid)}，忙则抛「对方正忙」。</li>
     * </ul>
     *
     * <h3>所以原来的写法错在哪</h3>
     * <p>旧代码写的是 <b>不带 userId 后缀</b>的裸键 {@code im:user:state}，并且把封禁写成
     * {@code 1}、解封写成 {@code 0}。三重问题：</p>
     * <ol>
     *   <li><b>写错了键</b>：{@code UserStateUtils} 拼的是 {@code im:user:state:{userId}}，
     *       裸键<b>没有任何读写方</b>，是一枚永远躺在 Redis 里的垃圾键
     *       （且解封时写 {@code 0} 不设 TTL，能一直留到下次重启）；</li>
     *   <li><b>语义错</b>：这枚键表达的是「正在通话」，<b>不是</b>「在线」也不是「被封禁」。
     *       被封禁 ≠ 正在忙，两者没有任何映射关系；</li>
     *   <li><b>真写进正确键反而更糟</b>：若写成 {@code im:user:state:{userId}=1}，
     *       会让该用户被误判为「通话中」，把他在 30 秒内的正常通话请求全部挡掉，而
     *       封禁本身<b>一点也不会因此生效</b>（box 侧读它的只有 Webrtc 呼叫前置校验）。</li>
     * </ol>
     *
     * <h3>正确的做法（本类的现状）</h3>
     * <p><b>不写这枚键</b>：封禁态一律以 kean 自己的
     * {@value #BANNED_KEY_PREFIX}{@code {userId}} 与 {@code sys_user.status} 为准。
     * 另外，封禁时顺手清掉该用户可能残留的忙线标记（见 {@link #deny(Long, String)} 的
     * {@code clearBusyState}）—— 那是 im-platform 真正会读的键，避免「被踢下线后
     * 30 秒内别人打给他仍提示对方正忙」。这是<b>清理 box 自己的临时状态</b>，
     * 不是用忙线去表达封禁。</p>
     */
    private static final String USER_STATE_PREFIX = "im:user:state";

    /**
     * box-im 的封禁标记键前缀：{@code im:user:denied:{userId}}（沿用上游 {@code IMRedisKey.IM_USER_DENIED}），
     * <b>无 TTL</b>，由 {@link #allow(Long)} 在解封时删除（对应「永久直到解封」的语义）。
     *
     * <p><b>✅ 上游 im-server 自带封禁校验，kean 不需要给 im-server 打任何补丁。</b>
     * box-im 4.0.0 的 {@code LoginProcessor.process}（{@code im-server} 模块）在验签通过、
     * 解析出 {@code userId} 之后就有：</p>
     * <pre>{@code
     * // 封禁/注销等拒绝建立长连接
     * if (Boolean.TRUE.equals(redisMQTemplate.hasKey(StrUtil.join(":", IMRedisKey.IM_USER_DENIED, userId)))) {
     *     ctx.channel().close();
     *     log.warn("用户不可用，拒绝连接,userId:{}", userId);
     *     return;
     * }
     * }</pre>
     *
     * <p>所以 kean 只要<b>写对键</b>（本常量）就能同时做到：① 踢掉当前连接
     * （{@link #forceLogout(Long, String)}）② <b>阻止被封用户重连</b>（im-server 启动时读这枚键）。
     * 上游 im-platform 的 {@code UserBannedConsumerTask} 封禁时写的也是这枚键，语义完全一致。</p>
     *
     * <p>⚠️ 历史遗留：本项目早期调查基于更旧的 box-im 版本，曾误判「im-server 没有封禁校验、
     * 必须自己打补丁」，并因此一度使用自造的键 {@code kean:im:banned:{userId}} —— 那个键
     * im-server 完全不认。现已改为上游键名，{@code docs/ops/im-server-patch.md} 也已改为
     * 「无需打补丁，只需对齐键名与密钥」。</p>
     */
    private static final String BANNED_KEY_PREFIX = "im:user:denied:";

    /**
     * box-im {@code IMTerminalType} 的全部终端码：WEB=0 / APP=1 / PC=2。
     *
     * <p>必须三个都试：{@code userServerIdKey} 是按 {@code (userId, terminal)} 分槽位的，
     * 同一用户在不同终端各占一个键、各有一条长连接。kean 签发的 token 里
     * {@code info.terminal} 由 {@code GET /api/im/token} 决定（默认 APP），
     * 所以踢人时不能只猜一个终端。</p>
     */
    private static final int[] TERMINALS = {0, 1, 2};

    /** box-im {@code IMTerminalType} 名字，仅用于日志。 */
    private static final String[] TERMINAL_NAMES = {"WEB", "APP", "PC"};

    /**
     * box-im {@code IMForceLogoutType} 的两个取值（im-platform 的 {@code AuthInterceptor}
     * 只用它来选提示语）。
     *
     * <ul>
     *   <li>{@code BANNED = 1} → 「账号已被封禁」（{@link #markBanned}）</li>
     *   <li>{@code UNREG = 2} → 「账号已注销」（{@link #markUnregistered}）</li>
     * </ul>
     *
     * <p>⚠️ 这两个数字必须与上游一致：{@code AuthInterceptor} 里写的是
     * {@code type.equals(IMForceLogoutType.UNREG.code()) ? "账号已注销" : "账号已被封禁"}，
     * 所以<b>任何非 2 的非空值都表现为「已被封禁」</b>。</p>
     */
    private static final int DENIED_TYPE_BANNED = 1;

    private static final int DENIED_TYPE_UNREG = 2;

    private final ImTokenService imTokenService;

    /**
     * kean 全站的字符串模板：本类<b>其余所有键</b>都继续用它写/读，
     * 保持与改造前<b>逐字节一致</b>（见 {@link #imDeniedRedis}）。
     */
    private final StringRedisTemplate redis;

    /**
     * <b>只用于封禁键</b> {@code im:user:denied:{userId}} 的 {@code RedisTemplate<String,Object>}
     * （Bean 名 {@code imDeniedRedisTemplate}，定义见 {@code RedisConfig}）。
     *
     * <p>为什么要单独一个模板：这枚键的<b>值必须是数字</b>（{@code Integer}），
     * 因为 im-platform 的 {@code AuthInterceptor} 会
     * {@code Integer type = (Integer) redisTemplate.opsForValue().get(key)} <b>强转</b>读取；
     * {@code StringRedisTemplate} 只能写字符串，写进去会让 im-platform
     * <b>每一个 REST 调用都抛 {@code ClassCastException}</b>（500）。</p>
     *
     * <p>⚠️ <b>不要拿它去写别的键</b>：它的值序列化是「带类型信息的 Jackson JSON」，
     * 与全站 {@code StringRedisTemplate} 的写法不同。本类里
     * {@code im:user:server_id:{userId}:{terminal}}（读）、{@code im:user:force_logout:{serverId}}（写）
     * 与 {@code im:user:state:{userId}}（删）<b>一律继续用 {@link #redis}</b>，
     * 保证与改造前完全一致。</p>
     */
    private final RedisTemplate<String, Object> imDeniedRedis;

    private final ObjectMapper objectMapper;

    public ImKickService(ImTokenService imTokenService,
                         StringRedisTemplate redis,
                         @Qualifier("imDeniedRedisTemplate") RedisTemplate<String, Object> imDeniedRedis,
                         ObjectMapper objectMapper) {
        this.imTokenService = imTokenService;
        this.redis = redis;
        this.imDeniedRedis = imDeniedRedis;
        this.objectMapper = objectMapper;
    }

    /**
     * 封禁：写封禁标记（永久，直到 {@link #allow(Long)}）+ 清掉可能残留的忙线标记
     * + 尽力踢掉 im-server 上的长连接。
     *
     * <p><b>本方法不抛异常</b>：任何一步失败都只记日志，继续尝试后面的步骤。
     * 调用方另外还会 try/catch 一层，保证绝不影响 kean 的封禁流程。</p>
     *
     * <p><b>不写 {@code im:user:state}</b>（曾经写过 {@code 1}，已删除）—— 那枚键是
     * im-platform 的 WebRTC 忙线标志，与封禁无关，理由见 {@code USER_STATE_PREFIX} 的注释。</p>
     *
     * @param userId 课安用户 id（= box-im 侧的 userId，因为 token 是我们自己签的）
     * @param reason 封禁原因，只用于日志，不出现在 im-server 的协议字段里
     */
    public void deny(Long userId, String reason) {
        if (!active() || userId == null) {
            return;
        }
        markBanned(userId, reason);
        clearBusyState(userId);
        forceLogout(userId, reason);
    }

    /**
     * 解封：删除封禁标记键。
     *
     * <p>被踢掉的连接不会自动回来 —— 用户需要重新走 {@code GET /api/im/token} 拿新 token 再连。</p>
     *
     * <p><b>这里不再碰 {@code im:user:state}</b>（旧代码会把裸键写成 {@code 0}）。
     * 忙线状态归 im-platform 的 {@code UserStateUtils} 管（30 秒 TTL 自动释放），
     * 解封时没有任何需要 kean 去「恢复」的东西。</p>
     */
    public void allow(Long userId) {
        if (!active() || userId == null) {
            return;
        }
        try {
            redis.delete(BANNED_KEY_PREFIX + userId);
            log.info("[IM 解封] 已删除封禁标记键 {}{}，用户可重新获取 IM token", BANNED_KEY_PREFIX, userId);
        } catch (Exception ex) {
            log.warn("[IM 解封] 清理 box-im 侧状态失败（不影响 kean 的解封流程），userId={}：{}",
                    userId, ex.getMessage());
        }
    }

    /**
     * 按 box-im 的 <b>FORCE_LOGOUT(2)</b> 通道下发强制下线。
     *
     * <h3>通道与消息格式（照抄 box-im master）</h3>
     * <p>下发方式是 <b>Redis List 队列</b>（不是 HTTP 接口）：</p>
     * <ul>
     *   <li>队列键：{@code im:user:force_logout:{serverId}}，
     *       其中 {@code serverId} 从 {@code im:user:server_id:{userId}:{terminal}} 读出
     *       （值就是 im-server 的 {@code IMServerGroup.serverId}）。键不存在则说明该终端不在线；</li>
     *   <li>写入方式：{@code opsForList().rightPush(key, value)}（与 im-server
     *       {@code LoginProcessor} 里「连到别的 im-server 时投递下线指令」的写法一致）；</li>
     *   <li>消息体：{@code IMForceLogoutInfo} 的 JSON，字段名必须是
     *       {@code userId}(Long) / {@code terminal}(Integer) / {@code devId}(String)。
     *       im-server 侧由 {@code PullForceLogoutTask}({@code @RedisMQListener(queue = "im:user:force_logout")})
     *       经 {@code RedisMQPullTask} 的 {@code jsonObject.toJavaObject(type)} 反序列化
     *       —— 默认 FastJson 反序列化，<b>不需要</b> {@code @type} 字段，
     *       所以用 Jackson 写出的同构 JSON 它能正常读；</li>
     *   <li>{@code devId} 传空串：{@code ForceLogoutProcessor} 的判断是
     *       {@code StrUtil.isEmpty(info.getDevId()) || !info.getDevId().equals(devId)}，
     *       空串会走「强制下线」分支，这正是封禁想要的。</li>
     * </ul>
     *
     * <p><b>⚠️ 前提</b>：真正的消费者 {@code PullForceLogoutTask} 就在 <b>im-server</b> 进程里
     * （它属于 im-server 模块，不需要 im-platform ✓）。因此<b>只要 im-server 在跑，这个踢人就生效</b>；
     * im-server 尚未部署时，这些指令只会安静地留在 Redis 队列中，不会生效（也不会报错）。</p>
     *
     * @return 成功推入队列的「在线终端」个数；IM 未启用或全部离线时为 {@code 0}
     */
    public int forceLogout(Long userId, String reason) {
        if (!active() || userId == null) {
            return 0;
        }
        Map<String, String> serverIds;
        try {
            serverIds = readServerIds(userId);
        } catch (Exception ex) {
            log.warn("[IM 强制下线] 读取 im-server 在线槽位失败，userId={}：{}", userId, ex.getMessage());
            return 0;
        }

        int pushed = 0;
        for (Map.Entry<String, String> entry : serverIds.entrySet()) {
            String serverId = entry.getValue();
            if (!StringUtils.hasText(serverId)) {
                continue;   // 该终端不在线（键不存在），无需下发
            }
            int terminal = Integer.parseInt(entry.getKey());
            try {
                redis.opsForList().rightPush(FORCE_LOGOUT_QUEUE_PREFIX + ":" + serverId.trim(),
                        forceLogoutJson(userId, terminal));
                pushed++;
                log.info("[IM 强制下线] 已下发 FORCE_LOGOUT(2) 到 im-server#{}, userId={}, terminal={}({}), 原因：{}",
                        serverId, userId, terminal, terminalName(terminal), reason);
            } catch (Exception ex) {
                log.warn("[IM 强制下线] 下发失败（不影响 kean 的封禁流程），userId={}, serverId={}：{}",
                        userId, serverId, ex.getMessage());
            }
        }
        if (pushed == 0) {
            log.debug("[IM 强制下线] userId={} 在 im-server 上无在线连接，无需下发", userId);
        }
        return pushed;
    }

    /**
     * IM 是否处于「已启用且密钥合格」状态。
     *
     * <p>复用 {@link ImTokenService#enabled()}，保证「token 能签发」与「踢人能生效」是同一个判据，
     * 不会出现一边开着一边关着的中间态。</p>
     */
    public boolean enabled() {
        return imTokenService.enabled();
    }

    /**
     * 所有对外动作的统一闸门：IM 未启用时一律 no-op。
     *
     * <p>用 DEBUG 而非 WARN —— 默认关闭 IM 是<b>正常状态</b>，
     * 每次封禁都打 WARN 会把日志刷满。</p>
     */
    private boolean active() {
        if (imTokenService.enabled()) {
            return true;
        }
        log.debug("[IM 联动跳过] IM_JWT_SECRET 未配置或长度不合格，IM 通道未就绪，"
                + "跳过 box-im 侧的封禁/踢人动作（kean 的封禁流程不受影响）");
        return false;
    }

    /**
     * 写 box-im 的封禁标记键 {@code im:user:denied:{userId}}（<b>无 TTL</b> —— 语义是「永久直到解封」），
     * 值为 <b>{@code Integer 1}</b>（{@code IMForceLogoutType.BANNED}）。
     *
     * <h3>⚠️ 为什么值必须是 {@code Integer} 而不是字符串</h3>
     * <p>上游 im-platform 的 {@code AuthInterceptor} 读这枚键时是<b>强转</b>：</p>
     * <pre>{@code
     * Integer type = (Integer) redisTemplate.opsForValue().get(
     *         StrUtil.join(":", IMRedisKey.IM_USER_DENIED, userSession.getUserId()));
     * if (type != null) { ... throw new GlobalException(tip); }
     * }</pre>
     * <p>强转<b>不做类型转换</b>：值若是字符串 {@code "1"}，这行会抛
     * {@code ClassCastException}（String 不能转 Integer）⇒ <b>im-platform 的每个 REST 调用都变 500</b>，
     * 而且这个拦截器在最前面，所以是「全站不可用」级别的坑。因此这里改用
     * {@link #imDeniedRedis}（序列化与 box-im 对齐）写入数字。</p>
     * <p>im-server 侧不受影响：它的 {@code LoginProcessor} 只用 {@code hasKey} 判断存在性，
     * 不看值 —— 也就是说改成数字对现有部署<b>只有好处、没有行为变化</b>。</p>
     *
     * <p>⚠️ 本方法<b>只写封禁键</b>；{@code im:user:server_id} / {@code im:user:force_logout}
     * 等其余键仍由 {@link #redis}（String 模板）处理，写法与改造前一致。</p>
     */
    private void markBanned(Long userId, String reason) {
        try {
            imDeniedRedis.opsForValue().set(BANNED_KEY_PREFIX + userId, Integer.valueOf(DENIED_TYPE_BANNED));
            log.info("[IM 封禁标记] 已写入 {}{} = {}（Integer，无 TTL，解封时删除）",
                    BANNED_KEY_PREFIX, userId, DENIED_TYPE_BANNED);
        } catch (Exception ex) {
            log.warn("[IM 封禁标记] 写入失败（不影响 kean 的封禁流程），userId={}：{}", userId, ex.getMessage());
        }
    }

    /**
     * 写 box-im 的「<b>已注销</b>」标记：{@code im:user:denied:{userId}} = <b>{@code Integer 2}</b>
     * （{@code IMForceLogoutType.UNREG}）。
     *
     * <p>与 {@link #markBanned} 同一枚键、同一套语义（值为 {@code Integer}、无 TTL、
     * 由 <b>解封/恢复</b>路径的 {@link #allow(Long)} 删除），只有<b>取值</b>不同：</p>
     * <ul>
     *   <li>{@code 1} = 封禁 → im-platform 回「账号已被封禁」（{@link #markBanned}，{@code deny} 已调用）；</li>
     *   <li>{@code 2} = 注销 → im-platform 回「账号已注销」（本方法）。</li>
     * </ul>
     *
     * <h3>⚠️ 本轮（阶段 A）刻意不接业务链路</h3>
     * <p>kean 的注销流程在 {@code AuthServiceImpl#deleteAccount}，<b>本轮一行不改</b>：
     * 只提供方法，等<b>阶段 F</b>（账号真相收尾 / 双轨一致性）再接上调用点
     * （见 {@code docs/ops/im-platform-migration.md} §2.4 的联动矩阵「注销」行
     * 与 §10.5 终态对照表第 4 项）。所以现在<b>没有任何调用方</b>，
     * 它的存在只为「注销态的同步」预留一个与封禁同形的入口。</p>
     *
     * @param userId 课安用户 id（= box 侧 userId，因为 token 是 kean 自己签的）
     */
    public void markUnregistered(Long userId) {
        if (userId == null) {
            return;
        }
        if (!active()) {
            return;
        }
        try {
            imDeniedRedis.opsForValue().set(BANNED_KEY_PREFIX + userId, Integer.valueOf(DENIED_TYPE_UNREG));
            log.info("[IM 注销标记] 已写入 {}{} = {}（Integer，无 TTL；阶段 F 接入注销流程）",
                    BANNED_KEY_PREFIX, userId, DENIED_TYPE_UNREG);
        } catch (Exception ex) {
            log.warn("[IM 注销标记] 写入失败（不影响 kean 的注销流程），userId={}：{}", userId, ex.getMessage());
        }
    }

    /**
     * 清掉该用户可能残留的 <b>im-platform WebRTC 忙线标记</b>
     * {@code im:user:state:{userId}}。
     *
     * <p>注意：这是<b>清理 box 自己的临时状态</b>，不是用忙线来表达封禁
     * （语义辨析见 {@code USER_STATE_PREFIX} 的注释）。存在的意义是：用户被封禁时若正好
     * 在通话中，{@code UserStateUtils} 给他留了 30 秒 TTL 的忙线键；人被踢下线后
     * 这 30 秒里别人打给他会得到「对方正忙」这种误导性提示。封禁时顺手删掉更干净。</p>
     *
     * <p>删不存在的键是 no-op，所以解封、重复封禁都不需要额外判断。</p>
     */
    private void clearBusyState(Long userId) {
        try {
            redis.delete(USER_STATE_PREFIX + ":" + userId);
        } catch (Exception ex) {
            log.debug("[IM 封禁标记] 清理忙线标记 {}{} 失败（可忽略）：{}",
                    USER_STATE_PREFIX + ":", userId, ex.getMessage());
        }
    }

    /**
     * 读<b>三个终端各自的</b> im-server 槽位（阶段 C-3 起的第二个调用方 = {@code ImMultiTerminalEchoService}）。
     *
     * <p>⚠️ 这是「照抄既有读法」而不是新写一套的落点：键名
     * {@code im:user:server_id:{userId}:{terminal}} 的拼法（含 {@code {}} hash tag）、
     * 键不存在 = 离线、值必须是正整数、脏值只 WARN 的取舍，全部与
     * {@link #readServerIds(Long)} 同源 —— 本方法只是把它<b>暴露</b>出去，一行拼键逻辑都没有新增。</p>
     *
     * <p><b>为什么暴露的是 Map 而不是「单个终端的读法」</b>：C-3 需要「每个终端各自的 serverId」
     * （它要按终端跳过当前终端），而 {@link #readServerIds(Long)} 用的是一次 {@code multiGet}
     * 拿三个键。如果只暴露单键读法，调用方为了看三个终端就得调三次，
     * 每次都退化成一次 {@code multiGet}（3 次往返 × 每端 3 个键 = 9 次读）。
     * 返回整个 Map 让调用方一次拿到全部，且语义与 {@code forceLogout} 的用法完全一致。</p>
     *
     * <p>⚠️ IM 未启用（{@code IM_JWT_SECRET} 不合格）时返回<b>空 Map</b>（而不是抛异常或返回 null）：
     * 调用方只需按「取不到 = 离线」处理即可，与 {@link #forceLogout} 的既有取舍一致。</p>
     *
     * @return 终端码字符串（{@code "0"}/{@code "1"}/{@code "2"}）→ serverId；
     *         <b>键一定存在</b>，值为 {@code null} 表示该终端离线
     */
    public Map<String, String> onlineServerIds(Long userId) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!active() || userId == null) {
            return result;
        }
        try {
            return readServerIds(userId);
        } catch (Exception ex) {
            // 与 forceLogout 的读槽位同一处理：读不到就当「没有在线终端」，
            // 绝不让一个 Redis 抖动外溢到调用方的发送路径上。
            log.warn("[IM 在线槽位] 读取 im:user:server_id 失败（按无在线终端处理），userId={}：{}",
                    userId, ex.getMessage());
            return result;
        }
    }

    /**
     * 读三个终端各自的 im-server 槽位，键为终端码的字符串形式。
     *
     * <p>用 {@code multiGet} + 固定顺序遍历，保证「请求顺序」与「返回下标」一一对应
     * （im-platform 的 {@code IMSender} 也是这么做的）。</p>
     */
    private Map<String, String> readServerIds(Long userId) {
        List<String> keys = new ArrayList<>(TERMINALS.length);
        for (int terminal : TERMINALS) {
            keys.add(USER_SERVER_ID_PREFIX + ":{" + userId + "}:" + terminal);
        }
        List<String> values = redis.opsForValue().multiGet(keys);
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < TERMINALS.length; i++) {
            String value = (values == null || i >= values.size()) ? null : values.get(i);
            result.put(String.valueOf(TERMINALS[i]), value);
        }
        return result;
    }

    /**
     * 拼 {@code IMForceLogoutInfo} 的 JSON。
     *
     * <p>字段名严格对齐 box-im 的 {@code com.bx.imcommon.model.IMForceLogoutInfo}
     * （Lombok {@code @Data} → getter 名 {@code getUserId/getTerminal/getDevId}）。
     * 用 Jackson 而非手写字符串，避免键名或转义出错。</p>
     */
    private String forceLogoutJson(Long userId, int terminal) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("userId", userId);
        info.put("terminal", terminal);
        info.put("devId", "");
        try {
            return objectMapper.writeValueAsString(info);
        } catch (Exception ex) {
            throw new IllegalStateException("IM 强制下线消息序列化失败", ex);
        }
    }

    /** 终端码 → 名字，仅供日志可读。 */
    private static String terminalName(int terminal) {
        return terminal >= 0 && terminal < TERMINAL_NAMES.length
                ? TERMINAL_NAMES[terminal]
                : String.format(Locale.ROOT, "UNKNOWN(%d)", terminal);
    }
}
