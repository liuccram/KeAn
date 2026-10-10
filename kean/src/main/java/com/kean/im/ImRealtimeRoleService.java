package com.kean.im;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * 阶段 C-4：<b>自研 WS（{@code /ws/chat}）的实时推送总闸</b> +
 * <b>「现在到底谁在干活」的一行汇总</b>。
 *
 * <h2>它解决什么问题</h2>
 * <p>终态里「聊天消息的实时推送」由 box 的 im-server（{@code ws://<im-host>:8878/im}）承担
 * （客户端<b>已经</b>同时连着自研 WS 与 box 的 {@code /im}）。所以自研 WS 上那段
 * <b>聊天推送</b>理论上是可以退役的 —— 但<b>不能直接删</b>，原因见下一节。</p>
 *
 * <h2>⚠️ 为什么默认必须是 {@code true}（默认关闭 = 保持现状）</h2>
 * <p>本项目已经实测到一条硬事实：<b>box 通道不产生 READ 事件</b>
 * （客户端 {@code imSocket} 只能映射 {@code MESSAGE} / {@code NOTICE}，
 * <b>没有 {@code READ}</b>）。而「对方已读」的<b>实时</b>推送，目前<b>唯一</b>的来源就是
 * 自研 WS 上的 {@code READ} 事件（{@code RealtimePublisher.read}）。
 * 因此把本开关默认成 {@code false} 会<b>立刻去掉一条用户可见的能力</b>，
 * 违反「零影响」这条既定模式。所以：{@code kean.im.legacy-ws-enabled} 默认 <b>{@code true}</b>。</p>
 *
 * <h2>开关语义（{@code false} 时到底关掉了什么）</h2>
 * <p>只关掉<b>聊天那一类的实时推送</b>，两条，且<b>不删代码、只用开关包住</b>：</p>
 * <table border="1">
 *   <caption>关掉 / 不关掉的分界（这是一条刻意的边界，不要扩大）</caption>
 *   <tr><th>推送点（文件:方法）</th><th>事件</th><th>{@code legacy-ws-enabled=false} 时</th><th>为什么</th></tr>
 *   <tr><td>{@code ChatWebSocketHandler.pushMessage}</td><td>{@code MESSAGE}</td>
 *       <td><b>跳过</b></td>
 *       <td>box 的 {@code /im} 已经承担消息实时性（kean → {@code im:message:private:{serverId}} → im-server 下行）</td></tr>
 *   <tr><td>{@code RealtimePublisher.read}</td><td>{@code READ}</td>
 *       <td><b>跳过</b>（本轮起：由更明确的 {@code kean.im.read-receipt-enabled} 单独把关）</td>
 *       <td>box 不推 READ（已实测）；<b>且本轮起客户端已不再展示「对方已读」（双勾已移除）</b>
 *           ⇒ 推它已无消费方，见下面「本轮新增的开关」</td></tr>
 *   <tr><td>{@code RealtimePublisher.notice}</td><td>{@code NOTICE}</td>
 *       <td><b>照推（不关）</b></td>
 *       <td>公告 / 通知类与聊天无关，且 {@code docs/ops/im-platform-migration.md} §1.4 明确要求保留</td></tr>
 *   <tr><td>{@code AccountBanServiceImpl.pushBanned}</td><td>{@code BANNED}</td>
 *       <td><b>照推（不关）</b></td>
 *       <td>封禁提示不是聊天推送；关掉会让被封用户看不到原因（同样被 §1.4 列为「没有 box 对等物、必须保留」）</td></tr>
 * </table>
 * <p>⚠️ <b>握手 / 连接 / 鉴权（{@code ChatHandshakeInterceptor} + {@code AUTH} 帧）以及 {@code PING}/{@code PONG}
 * 一律不动</b>：客户端可能仍然连着自研 WS，只是收不到上面那两类推送。</p>
 *
 * <h2>⭐ 本轮新增的开关：{@code kean.im.read-receipt-enabled}</h2>
 * <p>{@code legacy-ws-enabled} 是一把「聊天推送总闸」，它同时管着 {@code MESSAGE} 与 {@code READ}；
 * 而本轮起 {@code READ} 已经<b>没有任何消费方</b>（客户端双勾被移除，见下），
 * 两件事的退役节奏因此完全不同 —— 所以要一把<b>只管 READ</b> 的更明确的闸。</p>
 * <table border="1">
 *   <caption>两把闸的分工（{@code read-receipt-enabled} 优先判断）</caption>
 *   <tr><th>开关</th><th>默认</th><th>只管</th><th>{@code false} 时</th></tr>
 *   <tr><td>{@code kean.im.read-receipt-enabled}</td><td><b>{@code true}</b>（= 保持现状，照推）</td>
 *       <td>{@code RealtimePublisher.read} 的 {@code READ} 事件</td>
 *       <td>{@code READ} 不推；<b>{@code markRead} 的位点 / 状态 / 未读清零一律照旧</b></td></tr>
 *   <tr><td>{@code kean.im.legacy-ws-enabled}</td><td>{@code true}（= 保持现状）</td>
 *       <td>自研 WS 的 {@code MESSAGE} + {@code READ}</td>
 *       <td>两条聊天推送都不推（{@code NOTICE} / {@code BANNED} 照推）</td></tr>
 * </table>
 * <p>⚠️ <b>默认值纪律</b>：本开关默认 {@code true}（= 与新增它之前<b>一字不差</b>，仍然推 READ），
 * 部署本身<b>不改变任何线上行为</b>；真正切换由部署后改环境变量
 * {@code KEAN_IM_READ_RECEIPT_ENABLED=false} + 重启 kean 完成 ——
 * 这样出问题一条命令即可回退，与 {@code legacy-ws-enabled} / {@code unread-source} /
 * {@code read-source} 的既有做法完全一致。</p>
 * <p>⚠️ {@code false} 时<b>只少推一个事件</b>：{@code ChatServiceImpl.markRead} 的
 * box 已读回写、{@code a_read_seq/b_read_seq} 位点、{@code chat_message.status=3}、
 * {@code a_unread/b_unread} 清零、以及客户端每一次已读<b>上报</b>
 * （{@code createReadReporter} → {@code POST /api/chats/{id}/read}）全都照旧执行。
 * 因此它<b>不会</b>让「自己的未读角标」失效，也<b>不需要任何回填</b>。</p>
 *
 * <h2>⭐⭐ 关掉之后「已读」靠什么（这是 C-4 最容易出错的地方）</h2>
 * <p>⚠️ <b>本轮起这一段只对「回退到旧客户端」有意义</b>：客户端已经不再渲染双勾，
 * 「对方已读」这条信息<b>不再展示</b>，因此 {@code read-receipt-enabled=false}
 * <b>不会造成任何用户可见的能力缺失</b>。下面这段保留下来，是为了
 * ① 说明万一回退到旧客户端（仍画双勾）时已读靠什么推出来，
 * ② 与 {@link #riskNote()} 的文案保持一致（那条 WARN 也只在旧客户端下才有用户可见后果）。</p>
 * <p>链路是<b>数据推导</b>：客户端每 ≤8 秒做一次增量拉取
 * （{@code GET /api/chats/{id}/messages?afterSeq=N} → {@code ChatServiceImpl.catchUp}），
 * 而<b>已核实的结论是：增量拉取返回的 {@code ChatMessageVO} 里已经带 {@code status}</b>
 * （{@code status=3} 即已读）。两条读路径都带：</p>
 * <ul>
 *   <li>{@code read-source=kean}（默认）：{@code ChatServiceImpl.toMessageVo} 取
 *       {@code chat_message.status} —— 而 {@code ChatServiceImpl.markRead} <b>无论如何</b>都会把
 *       kean 侧的消息置 3，所以轮询一定推得出来；</li>
 *   <li>{@code read-source=box}：{@code ImOfflineQueryService.toMessageVos} 取
 *       {@code im_private_message.status} —— 但 box 侧的 {@code status=3} <b>只有</b>
 *       {@code ImUnreadQueryService.markReadInBox} 会写，而它<b>只在
 *       {@code kean.im.unread-source=box} 时真正执行</b>。
 *       ⇒ <b>{@code read-source=box} 且 {@code unread-source=kean} 时，box 侧的 status 永远是 1</b>，
 *       轮询推不出已读；此时若自研 WS 的 READ 又被关掉，<b>旧客户端的「对方已读」双钩将永远不出现</b>。</li>
 * </ul>
 * <p>所以本类在启动时会把这个<b>危险组合</b>显式 WARN 出来（见 {@link #riskNote()}）——
 * 把「无声失效」变成「日志里看得见的一行」。这正是本项目反复强调的
 * 「以为切了其实没切」的防线。</p>
 *
 * <h2>默认 {@code true} 时为什么零影响（逐条论证）</h2>
 * <ol>
 *   <li>本类<b>不持有连接、不读 Redis、不发 SQL、不建 DataSource</b>：构造期只读几个已经算好的布尔量；</li>
 *   <li>{@link #legacyWsPushEnabled()} 在 {@code ChatWebSocketHandler.pushMessage} 这个推送点上做的是
 *       {@code if (enabled) { ...原样... } else { 计数并 return; }}；
 *       {@link #readReceiptEnabled()} 在 {@code RealtimePublisher.read} 这个推送点上做的是同一件事 ——
 *       两个开关默认都是 {@code true}，走的就是<b>原来那一行</b>，一条语句、一个分支判断都不多；</li>
 *   <li>被包住的两个方法（{@code pushMessage} / {@code read}；{@code notice} / {@code broadcast}
 *       <b>刻意未被包</b>）的
 *       <b>签名、参数顺序、返回类型全部未改</b>，
 *       所以所有调用点一行都不用改（调用点清单见本轮的交付说明）；</li>
 *   <li>唯一新增的输出是一行 <b>INFO</b> 启动日志（{@link #summary()}）——
 *       与本项目其它 IM 开关（{@code [IM 镜像投递]} / {@code [IM 未读来源]} / {@code [IM 读取来源]} /
 *       {@code [IM 多端同步]}）风格一致，无副作用；</li>
 *   <li>它<b>不动</b> {@code .env*} / {@code application*.yml}（只靠 {@code @Value} 默认值）、
 *       <b>不加</b> Flyway、<b>不引</b>依赖、<b>不碰</b> im-platform。</li>
 * </ol>
 *
 * <h2>回退（一键、秒级、无需回填）</h2>
 * <p>把 {@code KEAN_IM_LEGACY_WS_ENABLED} / {@code KEAN_IM_READ_RECEIPT_ENABLED}
 * 设回 {@code true}（或直接删掉对应的那一行）并重启 kean：
 * 自研 WS 的 {@code MESSAGE} / {@code READ} 推送立刻恢复。因为这两个开关<b>只控制「推不推」</b>，
 * 不控制任何写入（kean 的消息、位点、{@code status=3} 一直在写），所以回退<b>不需要任何回填或重放</b>。</p>
 */
@Service
public class ImRealtimeRoleService {

    private static final Logger log = LoggerFactory.getLogger(ImRealtimeRoleService.class);

    /** 开关名（同时用于启动日志与 WARN 文案，避免拼写漂移）。环境变量形式：{@code KEAN_IM_LEGACY_WS_ENABLED}。 */
    public static final String PROPERTY_LEGACY_WS_ENABLED = "kean.im.legacy-ws-enabled";

    /**
     * 本轮新增的开关名：{@code READ}（已读回执）事件的推送闸。
     * 环境变量形式：{@code KEAN_IM_READ_RECEIPT_ENABLED}。<b>默认 {@code true}</b>（保持现状）。
     */
    public static final String PROPERTY_READ_RECEIPT_ENABLED = "kean.im.read-receipt-enabled";

    /** 消息实时推送通道（box 的 im-server）：判据与 {@code ImSenderService} 完全同源。 */
    private final ImSenderService imSenderService;

    /** 未读来源（B-3）：决定 box 的 {@code status=3} 会不会被写 —— 见类注释「关掉之后已读靠什么」。 */
    private final ImUnreadQueryService imUnreadQueryService;

    /** 读取来源（C-2）：决定轮询拉回来的 {@code status} 来自 kean 还是 box。 */
    private final ImOfflineQueryService imOfflineQueryService;

    /**
     * 开关：自研 WS 是否仍承担<b>实时推送</b>职责。<b>默认 {@code true}</b>
     * （= 与新增本类之前<b>一字不差</b>）。
     */
    private final boolean legacyWsEnabled;

    /**
     * 本轮新增：{@code READ}（已读回执）事件是否仍然推送。<b>默认 {@code true}</b>
     * （= 与新增本开关之前<b>一字不差</b>，仍然推）。
     *
     * <p>比 {@link #legacyWsEnabled} 更窄：它<b>只</b>管 {@code RealtimePublisher.read}
     * 这一个推送点，不动 {@code MESSAGE}，也不动 {@code NOTICE} / {@code BANNED}。
     * 部署本身零影响，真正的切换由部署后改环境变量
     * {@code KEAN_IM_READ_RECEIPT_ENABLED=false} 完成。</p>
     */
    private final boolean readReceiptEnabled;

    /** 被 {@link #legacyWsPushEnabled()} 挡掉的自研 WS 推送次数（无锁、只增；重启归零）。只用于观测，无副作用。 */
    private final LongAdder suppressedPushes = new LongAdder();

    /** 被 {@link #readReceiptEnabled()} 挡掉的 {@code READ} 推送次数（同上，只用于观测）。 */
    private final LongAdder suppressedReadReceipts = new LongAdder();

    public ImRealtimeRoleService(
            ImSenderService imSenderService,
            ImUnreadQueryService imUnreadQueryService,
            ImOfflineQueryService imOfflineQueryService,
            @Value("${kean.im.legacy-ws-enabled:true}") boolean legacyWsEnabled,
            @Value("${kean.im.read-receipt-enabled:true}") boolean readReceiptEnabled
    ) {
        this.imSenderService = imSenderService;
        this.imUnreadQueryService = imUnreadQueryService;
        this.imOfflineQueryService = imOfflineQueryService;
        this.legacyWsEnabled = legacyWsEnabled;
        this.readReceiptEnabled = readReceiptEnabled;
        // ③ 一行 INFO：把「每条实时职责由谁承担」一次性说清。
        // 我们踩过「以为切了其实没切」的坑（只看 /proc/<pid>/environ 只能证明环境变量被注入，
        // 不能证明 @Value 解析成功），所以这里把开关值 + 各通道的「配置值 / 生效值」一起打出来。
        log.info("[IM 实时职责] {}", summary());
        String risk = riskNote();
        if (risk != null) {
            // 危险组合：不是错误（服务照常），但会让某一条用户可见的能力<b>静默失效</b>。
            log.warn("[IM 实时职责] {}", risk);
        }
    }

    // ------------------------------------------------------------------
    // 开关
    // ------------------------------------------------------------------

    /**
     * 自研 WS 的实时推送是否仍然开着（{@code kean.im.legacy-ws-enabled}）。
     *
     * <p><b>默认 {@code true}</b>。为 {@code false} 时，调用方
     * （{@code ChatWebSocketHandler.pushMessage}）应当<b>跳过推送</b>并调用
     * {@link #recordSuppressedPush()} 计数；
     * <b>握手 / 连接 / 鉴权 / PING 不受影响</b>。</p>
     *
     * <p>⚠️ {@code READ} 的调用方（{@code RealtimePublisher.read}）自本轮起改为<b>优先</b>
     * 读 {@link #readReceiptEnabled()}（更窄的那把闸），只在它也为 {@code true} 时才回落到本方法。</p>
     */
    public boolean legacyWsPushEnabled() {
        return legacyWsEnabled;
    }

    /**
     * 本轮新增：{@code READ}（已读回执）事件的推送是否仍然开着
     * （{@code kean.im.read-receipt-enabled}）。
     *
     * <p><b>默认 {@code true}</b>（保持现状）。为 {@code false} 时，
     * {@code RealtimePublisher.read} 应当<b>跳过推送</b>并调用
     * {@link #recordSuppressedReadReceipt()} 计数 ——
     * <b>只少推一个事件</b>：{@code ChatServiceImpl.markRead} 的位点 / {@code status=3} /
     * 未读清零 / box 已读回写<b>一行都不受影响</b>，也不影响客户端的已读<b>上报</b>。</p>
     */
    public boolean readReceiptEnabled() {
        return readReceiptEnabled;
    }

    /** 记一次「被本开关挡掉的自研 WS 推送」（只增，用于观测）。 */
    public void recordSuppressedPush() {
        suppressedPushes.increment();
    }

    /** 记一次「被 {@code read-receipt-enabled} 挡掉的 READ 推送」（只增，用于观测）。 */
    public void recordSuppressedReadReceipt() {
        suppressedReadReceipts.increment();
    }

    /** 被挡掉的自研 WS 推送次数（只读快照，无副作用）。 */
    public long suppressedPushes() {
        return suppressedPushes.sum();
    }

    /** 被挡掉的 READ 推送次数（只读快照，无副作用）。 */
    public long suppressedReadReceipts() {
        return suppressedReadReceipts.sum();
    }

    /**
     * ③ 那行汇总的唯一文案来源（启动日志用它，将来若要在管理端展示也读它，避免两处口径漂移）。
     *
     * <p>报告四件事（用户清单里点名要的四条）：消息实时推送归谁 / 已读实时归谁（或靠轮询 ≤8s）/
     * 未读来源 / 读取来源，外加自研 WS 总闸与 {@code READ} 闸的当前值、已挡掉的推送数。</p>
     */
    public String summary() {
        return "消息实时推送=" + messagePushRole()
                + "；已读实时=" + readRealtimeRole()
                + "；未读来源=" + sourceOf(ImUnreadQueryService.PROPERTY_UNREAD_SOURCE,
                        imUnreadQueryService.configuredSource(), imUnreadQueryService.active())
                + "；读取来源=" + sourceOf(ImOfflineQueryService.PROPERTY_READ_SOURCE,
                        imOfflineQueryService.configuredSource(), imOfflineQueryService.active())
                + "；自研WS实时推送=" + (legacyWsEnabled ? "开（保持现状）" : "关（只保留连接/鉴权/PING，不再推 MESSAGE/READ）")
                + "（" + PROPERTY_LEGACY_WS_ENABLED + "=" + legacyWsEnabled + "）"
                + "；READ推送=" + (readReceiptEnabled ? "开（保持现状，仍推 READ）" : "关（不再推 READ；markRead 的位点/状态/未读清零照旧）")
                + "（" + PROPERTY_READ_RECEIPT_ENABLED + "=" + readReceiptEnabled + "）"
                + "；被挡掉的自研WS推送=" + suppressedPushes.sum()
                + "；被挡掉的READ推送=" + suppressedReadReceipts.sum()
                + "；回退方式=把 " + PROPERTY_LEGACY_WS_ENABLED + " / " + PROPERTY_READ_RECEIPT_ENABLED
                + " 设为 true（或删掉对应那行）并重启 kean（纯推送开关，无需回填）";
    }

    /**
     * 危险组合检查：返回 {@code null} 表示「当前组合没毛病」。
     *
     * <p>检查两件事，两件都是「某条能力会静默消失」的场景 ——
     * 它们<b>不会报错、不会有 500、不会有异常日志</b>，只会在用户那里表现为
     * 「消息不实时」或「已读双钩永远不出现」，所以必须在这里主动喊一声。</p>
     *
     * <p>⚠️ 本轮起第二条只在 {@code read-receipt-enabled=true}（<b>旧客户端才看双勾</b>）时才有意义：
     * {@code read-receipt-enabled=false} 时 READ 本来就不再推，「双钩永远不出现」已不是缺陷
     * —— 那种组合下不再喊第二条 WARN（否则会成为一条噪音 WARN）。</p>
     */
    public String riskNote() {
        if (legacyWsEnabled && readReceiptEnabled) {
            // 默认态：自研 WS 还在推 MESSAGE、READ 也还在推，下面两条风险都不存在。
            return null;
        }
        List<String> risks = new ArrayList<>(2);
        if (!legacyWsEnabled && !imSenderService.enabled()) {
            risks.add("消息实时推送【没有替代通道】：自研 WS 已关，而 box 通道未就绪"
                    + "（IM_JWT_SECRET 未配置/不足 32 字节，或 kean.im.mirror-enabled=false）"
                    + " ⇒ 新消息将没有【任何】实时推送，只能靠客户端轮询（≤8s）追");
        }
        // 只有「READ 还在推」时才需要提醒「推出来也没人用」的组合：
        // READ 已关时，双钩不出现是预期结果，不是风险。
        if (readReceiptEnabled && imOfflineQueryService.active() && !imUnreadQueryService.active()) {
            risks.add("已读实时【推不出来】：读取来源=box 但未读来源≠box，"
                    + "而 box 的 im_private_message.status=3 只由 kean.im.unread-source=box 时的 "
                    + "ImUnreadQueryService.markReadInBox 写 ⇒ 轮询拉回的 status 永远是 1，"
                    + "自研 WS 的 READ 又已关（" + PROPERTY_LEGACY_WS_ENABLED + "=false）"
                    + " ⇒ 旧客户端的对方已读双钩将【永远不出现】（新客户端已不展示双钩，无影响）。"
                    + "修法：把 kean.im.unread-source 也设为 box，或把 "
                    + PROPERTY_LEGACY_WS_ENABLED + " 设回 true");
        }
        if (risks.isEmpty()) {
            return null;
        }
        return String.join("；", risks)
                + "。⚠️ 以上均【不报错】，只表现为用户侧静默失效；"
                + "如需立刻恢复原状，把 " + PROPERTY_LEGACY_WS_ENABLED + " 设回 true 并重启 kean。";
    }

    // ------------------------------------------------------------------
    // 汇总文案的各段（都是纯函数，无副作用）
    // ------------------------------------------------------------------

    /** 「消息实时推送」归谁。 */
    private String messagePushRole() {
        if (imSenderService.enabled()) {
            return "box（im-server 8878 的 /im；kean→Redis 队列 im:message:private:{serverId}）";
        }
        return "无（IM 通道未就绪，消息只能靠客户端轮询追）"
                + (legacyWsEnabled ? "；当前自研WS=MESSAGE 仍在推（保底）" : "");
    }

    /** 「已读实时」归谁 —— 这一行是 C-4 的核心，写法刻意区分「推得出来」与「推不出来」。 */
    private String readRealtimeRole() {
        // 本轮起先看 READ 闸：它关了就没有任何实时已读（本轮已无消费方，属预期）。
        if (!readReceiptEnabled) {
            return "无实时推送（READ 推送已由 " + PROPERTY_READ_RECEIPT_ENABLED
                    + " 关闭；客户端本轮起已不展示「对方已读」，故无消费方。"
                    + "⚠️ kean 侧已读位点与 chat_message.status=3 照旧写入，可直接回退）";
        }
        if (legacyWsEnabled) {
            // 注意措辞：box 不推 READ（已实测），所以这里的「自研WS」不是可选项而是唯一来源。
            return "自研WS（/ws/chat 的 READ 事件；box 不推 READ，故这是唯一实时来源）";
        }
        if (!imOfflineQueryService.active()) {
            return "无实时推送 → 靠客户端轮询（≤8s）+ 数据推导（kean 的 chat_message.status=3，"
                    + "由 ChatServiceImpl.markRead 无条件写入）";
        }
        if (imUnreadQueryService.active()) {
            return "无实时推送 → 靠客户端轮询（≤8s）+ 数据推导（box 的 im_private_message.status=3，"
                    + "由 markReadInBox 写入）";
        }
        return "⚠️无实时推送，且轮询也推不出来（读取来源=box 但未读来源≠box ⇒ box 的 status 永远不是 3）。"
                + "⚠️ 该组合本身不会造成用户可见缺陷（本轮起 READ 与双勾都无消费方），"
                + "但它会让「未读口径收敛到 box」这件事不成立，切之前请把两个来源都设为 box";
    }

    /** 一个「配置值 / 是否真正生效」的片段（与各 IM 服务自己的启动日志同风格）。 */
    private static String sourceOf(String property, String configured, boolean effective) {
        String shown = configured == null || configured.isBlank() ? "(未配置)" : configured;
        return shown + "（" + property + "；实际生效=" + effective + "）";
    }
}
