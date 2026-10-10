package com.kean.im;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

/**
 * 阶段 C-3：把一条刚发出的私聊消息，按 box 的 <b>{@code sendToSelf}</b> 语义再投给
 * <b>发送者自己的「其它终端」</b>（多端同步）—— 但<b>只在开关打开时</b>。
 *
 * <h2>它解决什么问题（用户清单第 6 条）</h2>
 * <p>kean 现在的多端同步靠「每 8 秒的增量拉取去追」（能追到，但不实时）。
 * box 的做法是在投递时顺手给自己<b>其它</b>终端各推一份，于是另一台设备立刻出现这条消息。</p>
 *
 * <h2>⭐ box 的 {@code sendToSelf} 语义（依据 + 未证实点的边界）</h2>
 * <p>{@code docs/ops/im-platform-migration.md} §6.2 声称逐字摘录了
 * {@code im-client/.../sender/IMSender.java}：</p>
 * <pre>
 * if (message.getSendToSelf()) {
 *     Long senderId = sender.getId();
 *     List&lt;Integer&gt; terminals = IMTerminalType.codes();          // WEB=0, APP=1, PC=2
 *     for (Integer terminal : terminals) {
 *         if (terminal.equals(sender.getTerminal())) continue;   // ← 跳过"当前终端"
 *         selfKeys.add(IMRedisKey.userServerIdKey(senderId, terminal));
 *         selfOtherTerminals.add(terminal);
 *     }
 * }
 * for (int i = 0; i &lt; selfKeys.size(); i++) {
 *     Integer serverId = (Integer) serverIds.get(recvKeyCount + i);
 *     if (serverId != null) {                                     // 只有"在线"才投
 *         List&lt;IMUserInfo&gt; receivers = List.of(new IMUserInfo(senderId, selfOtherTerminals.get(i)));
 *         pushPrivateMessage(serverId, sender, receivers, false, message.getData()); // sendResult 固定 false
 *     }
 * }
 * </pre>
 * <p><b>⚠️ 与 C-2 同样的诚实声明：本机没有 box 源码</b>（{@code /opt/boxim-src/box-im} 不在本机），
 * 上面这段是从项目文档 §6.2 抄来的，<b>我没有现场读到源码</b>。用户本轮口述的语义与它一致
 * （「让同一个人在其他终端也收到；跳过 {@code sender.getTerminal()}；不回给自己当前终端」）。
 * 将来若读到源码发现不同，<b>改动点全部集中在本类</b>（三处：怎么枚举终端、怎么判在线、
 * 怎么构造 receivers），投递本身复用 {@link ImSenderService#pushPrivateToTerminals}，
 * 不需要动队列键或 JSON 形状。</p>
 *
 * <h2>⭐⭐ 怎么「跳过当前终端」（本轮最容易出错的地方）</h2>
 * <p>两重保证，缺一条都可能出现「自己收到自己的消息 ⇒ 重复气泡」：</p>
 * <ol>
 *   <li><b>不猜终端</b>：当前终端由 {@link ImTerminalResolver#currentRequestTerminal()} 给出，
 *       它与取票时写进 JWT 的 {@code info.terminal} <b>用的是同一份规则</b>
 *       （阶段 C 把 {@code ImController} 的私有方法原样搬进了那个类）。
 *       ⚠️ 这是本类最重要的一条依赖：如果发送路径按另一套规则推断终端，
 *       就会出现「跳过的是 APP、实际连的是 WEB」⇒ 消息推到发送者自己正在看的那个终端上；</li>
 *   <li><b>显式排除这个终端</b>：{@link #otherTerminals(int)} 只返回三个终端里<b>不等于</b>
 *       当前终端的那些（{@code WEB=0} / {@code APP=1} / {@code PC=2}），
 *       投递时 {@code receivers} 里也<b>只列</b>这些终端。</li>
 * </ol>
 * <p>⚠️ <b>刻意不做的一件事（踩过，已改回）</b>：曾经在这里加过「目标 {@code serverId} 减去
 * peer（对方）的 {@code serverId}」的去重，理由是「{@code im:message:private:{serverId}} 是按实例的队列，
 * 别写两条」。<b>那是错的</b>：反例是「我是 WEB(在 server#1)，对方的 APP 在 server#3，
 * 我的 PC 也在 server#3」——peer 那条投递的 {@code receivers} 只有对方本人，
 * 它<b>不会</b>把消息送给我的 PC；此时若把 server#3 减掉，我的 PC 就<b>收不到</b>这条自我同步，
 * 也就是「多端同步静默失效」（本功能唯一的目的）。
 * 所以现在<b>不做</b>任何 serverId 级去重：最坏只是同一实例上多入队一条，客户端按
 * {@code localId} 幂等去重，不会出现重复气泡。</p>
 *
 * <h2>⭐ 怎么判断「是否有其它终端在线」</h2>
 * <p>复用 box 的在线槽位键 <b>{@code im:user:server_id:{userId}:{terminal}}</b>：
 * 键存在且值为正 = 该终端在线，值即该终端所在 im-server 的 {@code serverId}；
 * 键不存在 = 离线。<b>读法直接调用 {@link ImKickService#onlineServerIds(Long)}</b>
 * （那是 {@link ImKickService} 里 {@code readServerIds} 已有读法的唯一出口，连同
 * {@code {}} hash tag 的拼法、脏值只 WARN 的取舍一起复用）—— <b>不新写一套拼键逻辑</b>：
 * 键名写错的后果是「永远认为没有其它终端在线」，那会表现为「多端同步静默失效」，且没有任何报错。</p>
 * <p>读的粒度是<b>一次 {@code multiGet} 拿三个键</b>（不是「每个终端一次」）：
 * 三个终端都要看，逐个读会变成 3 次往返。</p>
 * <p>由此得到的两条零开销语义：</p>
 * <ul>
 *   <li><b>单端在线时什么都不做</b>：其它终端的键全都不存在 ⇒ 目标集合为空 ⇒ 直接返回 0，
 *       <b>不查 peer 槽位、不序列化 JSON、不写 Redis</b>（绝大多数情况下的开销 =
 *       {@link ImKickService#onlineServerIds(Long)} 那一次 {@code multiGet}）；</li>
 *   <li>「对方（peer）不在线」<b>不影响</b>本类：自我同步与「对方能不能收到」是两件事
 *       （对方离线时 kean 的未读计数器与 box 的离线消息各自负责）。</li>
 * </ul>
 *
 * <h2>默认关闭：不配 = 一次 Redis 都不读</h2>
 * <p>开关 {@code kean.im.multi-terminal-echo-enabled}，默认 <b>false</b>。
 * false 时 {@link #echoAfterSend} 第一行就返回 0：<b>不读 Redis、不建 JSON、不写队列</b>，
 * 调用方（{@code ChatServiceImpl.send}）的行为与新增本类之前逐字节一致。</p>
 * <p>⚠️ 它<b>独立于</b> {@code kean.im.mirror-enabled}（队列镜像总闸）与
 * {@code kean.im.message-mirror-enabled}（MySQL 双写）、{@code kean.im.read-source}（C-2）、
 * {@code kean.im.unread-source}（B-3）：本类只负责「多投一条给自己其它终端」，
 * 其它四项关掉都不影响它的判断，反过来也一样（便于单独灰度/单独回退）。</p>
 *
 * <h2>失败策略：只 WARN + 计数，绝不外抛</h2>
 * <p>与 {@link ImSenderService} 完全同源的三条：</p>
 * <ol>
 *   <li>{@code echoAfterSend} 全包 try/catch，<b>任何异常都只 log.warn + 计数</b>，永不外抛 ——
 *       它被 {@code ChatServiceImpl.send} 的 {@code @Transactional} 包着，
 *       异常一旦穿出去就可能把「用户已经落库的那条消息」一起回滚；</li>
 *   <li>计数（{@link LongAdder}）<b>不改变吞异常的行为</b>：{@code increment()} 是 CAS、不抛异常，
 *       调用点都在 catch 块的第一行（原异常此刻已被捕获并即将 log.warn）；</li>
 *   <li>真正写队列的部分在 {@link ImSenderService#pushPrivateToTerminals} 里，它自己也 try/catch，
 *       所以「Redis 挂了」最多是本类的 {@code targets} 为空 + 一行 WARN。</li>
 * </ol>
 * <p>⚠️ <b>幂等性</b>：本类不做幂等保护，因为它<b>不落库</b>（只写 Redis 队列）。
 * 重复推送同一条消息是安全的：im-server 只是把同一个 data 再下行一次，
 * 客户端的 {@code chatMerge.dedupeKey} 以 {@code localId} 为第一优先级去重
 * （见 {@code ChatServiceImpl.send} 里 {@code idemKey} 的来源），不会出现第二个气泡。</p>
 */
@Service
public class ImMultiTerminalEchoService {

    private static final Logger log = LoggerFactory.getLogger(ImMultiTerminalEchoService.class);

    /** 开关名（用于启动日志与 WARN 文案，避免拼写漂移）。 */
    public static final String PROPERTY_ENABLED = "kean.im.multi-terminal-echo-enabled";

    private final ImTokenService imTokenService;

    /**
     * 在线槽位（{@code im:user:server_id:{userId}:{terminal}}）的<b>读入口</b>。
     * 刻意复用它的既有读法（键名拼法、脏值处理、{@code {}} hash tag），不新写一套。
     */
    private final ImKickService imKickService;

    /** 真正的队列投递（{@code im:message:private:{serverId}}），与 B 阶段的镜像投递同一个出口。 */
    private final ImSenderService imSenderService;

    /** 开关：是否把消息同步给自己其它终端。<b>默认 false</b>。 */
    private final boolean enabled;

    // —— 计数（无锁，只增；重启归零）——
    private final LongAdder attempts = new LongAdder();
    private final LongAdder pushed = new LongAdder();
    private final LongAdder skipped = new LongAdder();
    private final LongAdder failed = new LongAdder();

    public ImMultiTerminalEchoService(ImTokenService imTokenService,
                                      ImKickService imKickService,
                                      ImSenderService imSenderService,
                                      @Value("${kean.im.multi-terminal-echo-enabled:false}") boolean enabled) {
        this.imTokenService = imTokenService;
        this.imKickService = imKickService;
        this.imSenderService = imSenderService;
        this.enabled = enabled;
        // 启动即把「到底开没开」打出来（与 ImSenderService / ImUnreadQueryService / ImMessageMirrorService
        // 同一风格）：只看 /proc/<pid>/environ 只能证明环境变量被注入，不能证明 @Value 解析成功
        // （键名拼错时会静默沿用默认值 false）。这里把「配置 / IM 通道是否就绪 / 实际生效」三者都打出来。
        boolean tokenReady = imTokenService.enabled();
        log.info("[IM 多端同步] 配置 {}=「{}」→ IM 通道就绪（IM_JWT_SECRET 已配置且 ≥32 字节）={}，"
                        + "实际生效={}；语义=box 的 sendToSelf（只投给发送者自己的【其它】终端，"
                        + "强制跳过当前终端，永不回投当前终端）；在线判据=im:user:server_id:{{userId}}:{{terminal}}，"
                        + "单端在线时为 0 开销；失败策略=只 WARN + 计数，绝不影响发送；"
                        + "回退方式=把 {} 设为 false 并重启 kean",
                PROPERTY_ENABLED, enabled, tokenReady, enabled && tokenReady, PROPERTY_ENABLED);
        if (enabled && !tokenReady) {
            // 开了开关但 IM 通道没就绪（IM_JWT_SECRET 缺失/太短）：一切照旧，只是本类永远不投。
            // 这一行是给「以为开了其实没开」准备的 —— 那个坑我们踩过。
            log.warn("[IM 多端同步] {} 已打开，但 IM_JWT_SECRET 未配置或不足 32 字节，"
                            + "im-server 通道不可用，多端同步不会生效（发送与其它功能均不受影响）",
                    PROPERTY_ENABLED);
        }
    }

    /**
     * 本类是否真正会投递：<b>配置打开</b> 且 <b>IM 通道就绪</b>
     * （{@link ImTokenService#enabled()}，与 {@link ImSenderService#enabled()} 同一判据）。
     */
    public boolean enabled() {
        return enabled && imTokenService.enabled();
    }

    /** 计数的只读快照，格式：{@code attempts/pushed/skipped/failed}（无锁、无副作用）。 */
    public String statsSnapshot() {
        return attempts.sum() + "/" + pushed.sum() + "/" + skipped.sum() + "/" + failed.sum();
    }

    /**
     * 发送成功之后的<b>追加</b>动作：把这条消息同步给发送者的其它终端。
     *
     * <p><b>调用位置是硬约束</b>：必须在 kean 的消息<b>落库之后</b>
     * （{@code ChatServiceImpl.send} 里 {@code chatMessageMapper.insert} 之后，
     * 与 B-1 的镜像调用相邻），因为自我同步的 {@code data} 要带上落库后的真实
     * {@code localId} / {@code seqNo} / {@code createdAt} —— 客户端靠 {@code localId} 去重、
     * 靠 {@code seqNo} 推游标。用插入前的值会让另一台设备拿到对不上号的条目
     * （表现为「多端重复」或「游标倒退」）。</p>
     *
     * <p>⚠️ 本方法<b>不做任何落库</b>、<b>不读 box 的表</b>；最坏情况是「另一台设备晚 8 秒
     * 靠增量拉取追到」（即打开本开关之前的行为）。</p>
     *
     * @param userId          发送者（= 要同步给自己的那个人）
     * @param sessionId       kean 会话 id（客户端 {@code applyIncoming} 的准入条件，必须有）
     * @param msgType         kean 的消息类型（{@code "TEXT"} / {@code "IMAGE"}）
     * @param content         消息内容（图片时是对象存储 objectKey）
     * @param localId         幂等 id（兼任客户端的去重键）
     * @param seqNo           会话内序号（落库后的真实值）
     * @param createdAt       落库后的真实创建时间（不要在这里重新取 {@code now()}）
     * @param senderTerminal  发送者当前终端（{@link ImTerminalResolver#currentRequestTerminal()}），
     *                        <b>会被跳过</b>；非法值时回落到 {@link ImTerminalResolver#DEFAULT_TERMINAL}
     * @return 实际投递的队列个数；开关关闭 / 单端在线 / IM 未就绪时都是 {@code 0}
     */
    public int echoAfterSend(Long userId, Long sessionId, String msgType, String content,
                             String localId, Long seqNo, LocalDateTime createdAt, Integer senderTerminal) {
        if (!enabled()) {
            skipped.increment();
            return 0;
        }
        if (userId == null || sessionId == null || !StringUtils.hasText(content)) {
            // 参数不全：既不是投递尝试也不是故障，刻意不计数（与 ImSenderService 的参数校验同一取舍）。
            return 0;
        }
        int current = senderTerminal == null ? ImTerminalResolver.DEFAULT_TERMINAL : senderTerminal;
        if (!ImTerminalResolver.isValid(current)) {
            // 非法终端（理论上到不了这里：取票时也会回落到 APP）。回落而不是放弃：
            // 放弃会让「多端同步静默失效」，而回落最多是「跳过了一个终端」。
            log.debug("[IM 多端同步] 终端码 {} 非法，按默认终端 {} 处理", current, ImTerminalResolver.DEFAULT_TERMINAL);
            current = ImTerminalResolver.DEFAULT_TERMINAL;
        }
        try {
            List<Integer> others = otherTerminals(current);
            if (others.isEmpty()) {
                skipped.increment();
                return 0;
            }
            // 其它终端 → 各自的在线 im-server 槽位（键不存在 / 值为空 = 该终端离线，跳过）
            // 一次 multiGet 拿三个键（ImKickService.onlineServerIds），不按终端逐次读。
            Map<String, String> mySlots = imKickService.onlineServerIds(userId);
            Map<Integer, String> onlineByOtherTerminal = new LinkedHashMap<>();
            for (Integer terminal : others) {
                String serverId = mySlots.get(String.valueOf(terminal));
                if (StringUtils.hasText(serverId)) {
                    onlineByOtherTerminal.put(terminal, serverId.trim());
                }
            }
            // ⚠️ 这里【刻意不做】「与 peer 那条投递按 serverId 去重」。
            //    曾经写成「目标 serverId 减去 peer 的 serverId」，那是错的 —— 反例：
            //    「我是 WEB(在 server#1)，peer 的 APP 在 server#3，我的 PC 也在 server#3」。
            //    peer 那条投递的 receivers 只有 peer 本人，所以它【不会】把消息送给我的 PC；
            //    若此时按 serverId 把 server#3 减掉，我的 PC 就【收不到】这条自我同步
            //    —— 也就是「多端同步静默失效」，而它恰恰是本功能唯一的目的。
            //    现在改为「不减去」：即使 peer 与我的其它终端恰好在同一个 im-server 上，
            //    最坏也只是同一个实例上多入队一条（receivers 里只有我自己的终端，
            //    客户端按 localId 幂等去重），不会出现重复气泡。
            if (onlineByOtherTerminal.isEmpty()) {
                // ⭐ 绝大多数请求走这一条：只有一台设备在线 ⇒ 什么都不做（零队列写入）。
                skipped.increment();
                log.debug("[IM 多端同步] userId={} 除当前终端 {} 外没有其它终端在线，跳过自我同步",
                        userId, ImTerminalResolver.name(current));
                return 0;
            }
            Set<String> targetServerIds = new LinkedHashSet<>();
            List<Integer> targetTerminals = new ArrayList<>(onlineByOtherTerminal.size());
            for (Map.Entry<Integer, String> entry : onlineByOtherTerminal.entrySet()) {
                targetServerIds.add(entry.getValue());
                targetTerminals.add(entry.getKey());
            }
            attempts.increment();
            String desc = "多端自我同步 userId=" + userId + " 当前终端=" + ImTerminalResolver.name(current)
                    + " 其它终端=" + terminalNames(targetTerminals);
            int pushedCount = imSenderService.pushPrivateToTerminals(targetServerIds, targetTerminals,
                    // recvId 传发送者自己：这条 data 在客户端看来就是「我自己发的、发给我自己的」，
                    // 客户端按 localId 去重后不会与 peer 那条重复显示。
                    userId, userId, sessionId, msgType, content, localId, seqNo, createdAt, desc);
            if (pushedCount > 0) {
                pushed.add(pushedCount);
            }
            return pushedCount;
        } catch (Exception ex) {
            failed.increment();
            // ⚠️ 这里【绝不】外抛：调用方正处在 @Transactional 里（ChatServiceImpl.send），
            //    异常一旦穿出去就可能把用户已经落库的消息一起回滚。
            log.warn("[IM 多端同步] 自我同步失败（不影响本次发送），userId={}, sessionId={}：{}",
                    userId, sessionId, ex.getMessage());
            return 0;
        }
    }

    /**
     * 除当前终端外的终端列表（{@code WEB=0} / {@code APP=1} / {@code PC=2}，固定顺序）。
     *
     * <p>⚠️ 顺序固定（{@link ImTerminalResolver#TERMINALS} 的声明顺序），
     * 便于日志与人工核对；返回值<b>永远与「是不是当前终端」成对</b>，
     * 不要在这里加别的过滤条件（在线判断在 {@link #echoAfterSend} 里单独做，
     * 这样「为什么没投」在日志里是可分辨的）。</p>
     */
    private static List<Integer> otherTerminals(int currentTerminal) {
        List<Integer> others = new ArrayList<>(ImTerminalResolver.TERMINALS.length);
        for (int terminal : ImTerminalResolver.TERMINALS) {
            if (terminal != currentTerminal) {
                others.add(terminal);
            }
        }
        return others;
    }

    /** 终端列表 → 日志用的名字串（例如 {@code [WEB, PC]}）。 */
    private static String terminalNames(List<Integer> terminals) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < terminals.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(ImTerminalResolver.name(terminals.get(i)));
        }
        return sb.append(']').toString();
    }
}
