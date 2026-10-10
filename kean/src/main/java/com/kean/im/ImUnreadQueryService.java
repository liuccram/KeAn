package com.kean.im;

import com.kean.entity.ChatSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * 阶段 B-3：把<b>私聊未读/已读的权威口径</b>从 kean 的会话计数器
 * （{@code chat_session.a_unread / b_unread}）切到 box 的<b>消息状态</b>
 * （{@code im_platform.im_private_message.status}）—— 但<b>只在开关打开时</b>。
 *
 * <h2>为什么要有这个类（一句话）</h2>
 * <p>终态里「未读」归 box（见 {@code docs/ops/im-platform-migration.md} §6.3.3），
 * 而 box 的未读<b>不是一张表上的计数器</b>，是<b>消息 {@code status} 的函数</b>：
 * {@code status = 3} 即已读，其余（0 待推送 / 1 已送达）都算未读。
 * 因此「按 box 口径算未读」= 一条 {@code COUNT(*) WHERE ... AND status < 3}。
 * 本类就是这个函数的唯一落点。</p>
 *
 * <h2>⭐ 本类<b>只</b>做两件事</h2>
 * <ol>
 *   <li><b>读</b>：按 box 语义统计未读数（{@link #unreadCount} / {@link #unreadCounts} /
 *       {@link #unreadCountsByConvKey}）；</li>
 *   <li><b>写</b>：把「已读到 maxSeq」单向写进 box（{@link #markReadInBox}）。</li>
 * </ol>
 * <p>{@code ChatServiceImpl} 在写 box 成功/失败之后，<b>照旧</b>维护 kean 自己的位点、
 * {@code chat_message.status=3} 与 {@code a_unread/b_unread} —— 也就是说
 * <b>本类从不负责「kean 侧要不要更新」</b>，那是调用方的事（原则见下）。</p>
 *
 * <h2>⭐⭐ 四条硬约束（逐条对应方案里的原则，改动本类前请先读）</h2>
 * <ol>
 *   <li><b>默认关闭、不配零影响。</b>开关 {@code kean.im.unread-source}，取值
 *       {@code kean}（默认，不配即此）/ {@code box}。值为 {@code kean} 时
 *       {@link #active()} 恒为 {@code false}，读方法<b>一条 SQL 都不发</b>、
 *       写方法<b>直接返回 {@code false}</b> —— 与新增本类之前逐字节一致。
 *       ⚠️ 开关关闭时仍然<b>创建</b>了 {@link JdbcTemplate}（<b>不</b>新建 DataSource，
 *       只持有主 DataSource 的引用），但不持有连接、不发 SQL；</li>
 *   <li><b>失败绝不影响聊天页。</b>读方法一律 try/catch，失败只 {@code log.warn}（同一原因只打一次，
 *       见 {@link #warnOnce}）并返回 {@code null}/{@code null} 值，让调用方<b>回退</b>到 kean 既有算法。
 *       <b>「查 box 失败 ⇒ 聊天页打不开」是不允许发生的</b>；</li>
 *   <li><b>box 的写不污染 kean 的事务。</b>{@link #markReadInBox} 跑在
 *       {@code REQUIRES_NEW} 的<b>独立事务</b>里（见 {@link #boxTemplate}），
 *       异常在方法内部被吃掉（返回 {@code false}）⇒ 即使调用方
 *       （{@code ChatServiceImpl.markRead}）正处在 {@code @Transactional} 中，
 *       box 的失败也不可能把外层事务标记成 {@code rollbackOnly}。
 *       理由与 {@link ImMessageMirrorService} 完全同源（那里踩过这个坑，见其类注释「失败策略」第 2 条）；</li>
 *   <li><b>被读的 SQL 只碰 {@code im_platform}。</b>不读 box 的 Redis 位点
 *       （{@code im:readed:private:position:*} 的口径归阶段 C），不跨库 JOIN，
 *       也不写 kean 的任何表。</li>
 * </ol>
 *
 * <h2>⭐ 统计维度：为什么用 {@code (recv_id, conv_key)} 而不是只用其中一个</h2>
 * <p>{@code PrivateMessageServiceImpl} 的源码本地不存在（{@code /opt/boxim-src/box-im} 不在本机），
 * 因此<b>未读数「是否真的等于 status &lt; 3 的条数」这一点是「未证实、按 kean 现有语义实现」</b>
 * （用户已核实的部分是：{@code status} 取值 0 PENDING / 1 DELIVERED / 2 RECALL / 3 READED，
 * 且<b>没有 read_time 列</b> ⇒ 已读只有 {@code status=3} 这一个标记）。</p>
 * <p>两列都带上的理由：</p>
 * <ul>
 *   <li>{@code recv_id = 我} 是「这条消息是发给我的」的<b>直接</b>判据
 *       （box 的 {@code im_private_message} 本来就为收件人查询建了 {@code key idx_recv_id}）；
 *       这条能挡住「我发出的消息」被算成自己的未读 —— 只用 {@code conv_key} 挡不住；</li>
 *   <li>{@code conv_key = min_max} 把结果限制在<b>会话维度</b>（接口是按 sessionId 要未读数的），
 *       同时让「一个用户多个会话」的批量查询可以按会话分组；</li>
 *   <li>两者都是双写时<b>我们显式写入</b>的列（{@code recv_id} = 会话里的另一方、
 *       {@code conv_key} = {@link ImMessageMirrorService#convKey(Long, Long)}，已逐字核对 box 的
 *       {@code ConvUtil.buildConvKey}），因此这里不存在「猜 box 的拼法」的风险。</li>
 * </ul>
 * <p>⚠️ 顺带说明为什么<b>不用</b>「按 {@code id} 或 {@code seq_no} 与已读位点比较」的写法：
 * kean 的位点在 {@code chat_session.a_read_seq/b_read_seq}，box 侧的等价物在 Redis
 * （{@code im:readed:private:position:*}，阶段 C 才接手）。在本轮口径里，
 * <b>直接数「未读消息条数」比「拿位点推算条数」少一个可失败的中间量</b>，
 * 也不会把「位点前进但消息 status 没跟着改」这类分叉掩盖掉。</p>
 *
 * <h2>性能与索引（⚠️ 需要运维侧知道的一件事）</h2>
 * <p>建表 SQL（{@code docs/sql/im-platform.sql}）上 {@code im_private_message} 只有三个索引：
 * {@code idx_conv_key_seq_no(conv_key, seq_no)}、{@code idx_send_recv_id(send_id, recv_id, id)}、
 * {@code idx_recv_id(recv_id)}。上面这条未读统计<b>没有完全覆盖它的索引</b>：
 * 优化器会走 {@code idx_recv_id}（或 {@code conv_key} 前缀）取候选行，再回表过滤
 * {@code conv_key} 与 {@code status}。特征是<b>「一个用户的全部历史消息」量级的扫描</b>，
 * 在阶段 B 的体量下可接受；若将来单用户消息量很大、聊天列表接口变慢，
 * 应当加 <b>{@code KEY idx_recv_conv_status (recv_id, conv_key, status, seq_no)}</b>
 * （与 B-1 的唯一索引 {@code uk_send_local(send_id, local_id)} 一样，
 * 属于<b>服务器上单独执行的 DDL，不走 Flyway</b>，因为 {@code im_platform} 是另一个库）。
 * 本轮<b>刻意不动</b> box 的表结构。</p>
 *
 * <h2>与 {@link ImMessageMirrorService} 的关系（⚠️ 两个开关互相独立）</h2>
 * <table border="1">
 *   <caption>两份开关的边界</caption>
 *   <tr><th></th><th>{@code kean.im.message-mirror-enabled}</th><th>{@code kean.im.unread-source}</th></tr>
 *   <tr><td>管什么</td><td>kean → box 的<b>消息双写</b>（B-1）</td><td>未读的<b>读取来源</b>与已读的<b>写回</b>（B-3）</td></tr>
 *   <tr><td>默认</td><td>{@code false}（关）</td><td>{@code kean}（关）</td></tr>
 *   <tr><td>关掉后</td><td>不写 box 的影子行</td><td>不读 box、不写 box，未读回到 kean 计数器</td></tr>
 * </table>
 * <p>⚠️ 两者的<b>唯一</b>耦合是数据面的：{@code unread-source=box} 而
 * {@code message-mirror-enabled=false} 时，box 里可能压根没有最近的消息，
 * 未读数会偏小（这是可预期的，不是 bug）。所以要切到 {@code box}，
 * 前提是 B-1 的双写已经<b>开着</b>并跑过一段（验收见 §3.B.1）。</p>
 *
 * <h2>回退（把开关设回 {@code kean} 并重启 kean）</h2>
 * <p>因为 {@code ChatServiceImpl} 在 {@code box} 模式下<b>仍然</b>维护
 * {@code a_unread/b_unread} 与 kean 的消息 {@code status=3}（原则 2、3），
 * 回退时 kean 侧的数据是<b>热的</b>：切回去立刻就是正确的未读数，不需要回填、
 * 不需要重放任何事件。这一点是本类刻意「只读 box、不接管 kean 的写」的原因。</p>
 */
@Service
public class ImUnreadQueryService {

    private static final Logger log = LoggerFactory.getLogger(ImUnreadQueryService.class);

    /** 跨库全限定表名（同实例跨 schema，复用主 DataSource；<b>不新建 DataSource</b>）。 */
    private static final String TABLE = "im_platform.im_private_message";

    /**
     * box {@code MessageStatus.READED.code() = 3}。
     *
     * <p>已读的<b>唯一</b>标记（box 的 {@code im_private_message} 没有 {@code read_time} 列，
     * 已核实）。于是「未读」= {@code status < 3}。</p>
     */
    private static final int BOX_STATUS_READED = 3;

    /** 未读判据的 SQL 片段，集中一处维护（读与写两侧必须同源，否则会出现「算得出、清不掉」）。 */
    private static final String UNREAD_PREDICATE = "status < " + BOX_STATUS_READED;

    /** 开关名（同时用于启动日志与告警正文，避免文案与配置项拼写漂移）。 */
    public static final String PROPERTY_UNREAD_SOURCE = "kean.im.unread-source";

    /** 开关取值：未读来源仍是 kean（默认）。 */
    public static final String SOURCE_KEAN = "kean";

    /** 开关取值：未读来源是 box（{@code im_private_message.status}）。 */
    public static final String SOURCE_BOX = "box";

    /**
     * 单次批量查询最多带多少个 {@code conv_key}。
     *
     * <p>取 200：占位符 400 个（{@code recv_id} 1 个 + {@code IN} 里 200 个 ×2 条 SQL），
     * 远低于 MySQL 的包大小限制；会话列表接口一次也就返回几十个会话。</p>
     */
    private static final int CONV_KEY_CHUNK = 200;

    /** 数据源不可用（或解析失败）时为 {@code null}：此时所有读方法都回退、写方法都返回 false。 */
    private final JdbcTemplate boxJdbc;

    /**
     * box 侧写入的<b>独立事务模板</b>（{@code REQUIRES_NEW}），与
     * {@link ImMessageMirrorService} 的实现同源。解析不到事务管理器时为 {@code null}，
     * 此时退化成「直接用 JdbcTemplate 执行」——那种情况下第 2、3 条约束仍然有效
     * （异常被吞掉），只是少了一层事务隔离。
     */
    private final TransactionTemplate boxTemplate;

    /** 开关的原始取值（用于启动日志原样回显，便于发现「拼错键名」）。 */
    private final String configuredSource;

    /** 开关是否解析为 {@code box}（大小写不敏感、去空白）。 */
    private final boolean boxSource;

    // —— 计数（无锁，只增；重启归零）：[IM 未读来源] 的观测口径 ——
    private final LongAdder readHits = new LongAdder();
    private final LongAdder readFallbacks = new LongAdder();
    private final LongAdder readPushes = new LongAdder();
    private final LongAdder readRows = new LongAdder();
    private final LongAdder readWrites = new LongAdder();
    private final LongAdder readWriteFailures = new LongAdder();

    /**
     * 「本进程已经为某个失败原因打过 WARN 了」的标记。
     *
     * <p>为什么需要它：聊天列表是<b>高频</b>接口，而 box 不可用是<b>持续</b>状态。
     * 不做抑制的话，一次 box 故障会以「每个请求 × 每个会话」的速率刷日志。
     * 这里只在<b>第一次</b>失败时打 WARN（带完整异常 message），之后降为 DEBUG
     * —— 与 {@link ImAlertService} 的冷却同一个思路，但不依赖它（本类绝不能因为告警链路出问题而失败）。</p>
     */
    private final AtomicBoolean failureWarned = new AtomicBoolean(false);

    public ImUnreadQueryService(
            ObjectProvider<DataSource> dataSourceProvider,
            ObjectProvider<PlatformTransactionManager> transactionManagerProvider,
            @Value("${kean.im.unread-source:kean}") String unreadSource
    ) {
        this.configuredSource = unreadSource;
        this.boxSource = SOURCE_BOX.equalsIgnoreCase(unreadSource == null ? "" : unreadSource.trim());
        this.boxJdbc = resolveJdbcTemplate(dataSourceProvider);
        this.boxTemplate = resolveTransactionTemplate(transactionManagerProvider);
        // 构造期就能算出的「实际生效」：配置为 box 且数据源可用。
        boolean effective = boxSource && this.boxJdbc != null;
        // 启动即把「到底切没切」打出来。我们踩过「以为切了其实没切」的坑：
        // 只看 /proc/<pid>/environ 只能证明环境变量被注入，不能证明 @Value 解析成功
        // （键名拼错时会静默沿用默认值 kean）。所以这里把「配置值 / 生效值 / 实际可用」三者都打出来。
        log.info("[IM 未读来源] 配置 {}=「{}」→ 解析结果={}（{}），实际生效={}；"
                        + "box 读通道={}，box 写事务={}；目标表={}（主 DataSource，跨库全限定名，"
                        + "不新建 DataSource）；未读判据={}；已读回写={}（box 的 status 置 {}）；"
                        + "⚠️ 未读来源=box 时 kean 的 a_unread/b_unread 仍继续维护（回退要用），"
                        + "回退方式=把 {} 设回 {} 并重启 kean",
                PROPERTY_UNREAD_SOURCE, configuredSource, boxSource,
                boxSource ? "未读取自 box 的消息 status" : "未读取自 kean 的 chat_session.a_unread/b_unread",
                effective, boxJdbc == null ? "不可用（无 DataSource）" : "可用",
                boxTemplate == null ? "复用调用方连接（缺 PlatformTransactionManager）" : "REQUIRES_NEW（独立事务）",
                TABLE, UNREAD_PREDICATE, BOX_STATUS_READED, BOX_STATUS_READED,
                PROPERTY_UNREAD_SOURCE, SOURCE_KEAN);
        if (boxSource && boxJdbc == null) {
            // 最坏的一种组合：开关切到了 box，但数据源拿不到 ⇒ 未读会永久走回退（不报错，但也没切成功）。
            log.warn("[IM 未读来源] {} 已配置为 {}，但没有可用的 DataSource，"
                            + "所有未读查询都会回退到 kean 的既有算法（接口不受影响）",
                    PROPERTY_UNREAD_SOURCE, SOURCE_BOX);
        }
        if (!boxSource && unreadSource != null && !unreadSource.isBlank() && !SOURCE_KEAN.equalsIgnoreCase(unreadSource.trim())) {
            // 值写错（例如 "Box " 之外还有拼写错误、或想写 true/false）：静默回落成默认值最危险，
            // 所以这里显式告警一次，把「你以为切了」变成日志里看得见的一行。
            log.warn("[IM 未读来源] {} 的取值「{}」不是 {} / {}，已按默认值 {} 处理（未读仍取自 kean）",
                    PROPERTY_UNREAD_SOURCE, unreadSource, SOURCE_KEAN, SOURCE_BOX, SOURCE_KEAN);
        }
    }

    // ------------------------------------------------------------------
    // 开关
    // ------------------------------------------------------------------

    /**
     * 未读是否真的由 box 驱动：<b>配置为 {@code box}</b> 且 <b>数据源可用</b>。
     *
     * <p>两个条件缺一不可：配置成 {@code box} 但数据源拿不到时，返回 {@code false}
     * 会让调用方直接走 kean 的既有算法，而不是每个请求都去尝试一次注定失败的查询。</p>
     */
    public boolean active() {
        return boxSource && boxJdbc != null;
    }

    /** 原始配置值（供 {@link ImMessageMirrorService} 的比对告警写清「当前生效的是哪一侧」）。 */
    public String configuredSource() {
        return configuredSource;
    }

    /**
     * 计数的只读快照，格式：{@code hits/fallbacks/pushes/rows/writes/writeFailures}
     * （供日志与人工核对；无锁、无副作用）。
     *
     * <table border="1">
     *   <caption>各计数含义</caption>
     *   <tr><th>字段</th><th>含义</th></tr>
     *   <tr><td>{@code hits}</td><td>成功从 box 取到未读数的<b>查询</b>次数（含批量查询里的每个会话）</td></tr>
     *   <tr><td>{@code fallbacks}</td><td>box 查询失败（或数据源不可用）而<b>回退到 kean</b> 的次数</td></tr>
     *   <tr><td>{@code pushes}</td><td>{@code ChatServiceImpl.markRead} 调用 box 已读回写的次数</td></tr>
     *   <tr><td>{@code rows}</td><td>被 box 从「未读」改成「已读」的<b>行数</b></td></tr>
     *   <tr><td>{@code writes}</td><td>box 已读回写<b>执行成功</b>的次数（与 {@code rows} 区分：0 行也算成功）</td></tr>
     *   <tr><td>{@code writeFailures}</td><td>box 已读回写失败的次数（kean 侧照写，见类注释）</td></tr>
     * </table>
     */
    public String statsSnapshot() {
        return readHits.sum() + "/" + readFallbacks.sum() + "/" + readPushes.sum()
                + "/" + readRows.sum() + "/" + readWrites.sum() + "/" + readWriteFailures.sum();
    }

    // ------------------------------------------------------------------
    // ① 读：未读数（box 语义）
    // ------------------------------------------------------------------

    /**
     * 单个会话的未读数（box 语义）。
     *
     * <p>{@code session} 必须是<b>该用户参与的</b>会话，且其两端 id 都已知；否则返回 {@code null}
     * （= 调用方应当回退到 kean 的算法）。</p>
     *
     * @return box 算出的未读条数；<b>{@code null} 表示「没算出来」（开关未开 / 数据源不可用 / 查询失败）</b>
     */
    public Long unreadCount(Long userId, ChatSession session) {
        if (!active() || userId == null || session == null) {
            return null;
        }
        String convKey = convKeyOf(userId, session);
        if (convKey == null) {
            return null;
        }
        try {
            Long count = queryUnreadCount(userId, convKey);
            readHits.increment();
            return count;
        } catch (Exception ex) {
            readFallbacks.increment();
            warnOnce("按会话统计未读", "userId=" + userId + ", sessionId=" + session.getId()
                    + ", convKey=" + convKey, ex);
            return null;
        }
    }

    /**
     * 批量：{@code sessionId → 该会话里我的未读数}（聊天列表用，尽量少发 SQL）。
     *
     * <p>返回的 Map <b>只包含查成功的会话</b>；查失败的（或不在 {@code sessions} 里的）
     * 一律<b>不出现</b>，由调用方对缺失项回退到 kean 的计数器 —— 这样一次查询失败
     * 不会把整页未读变成 0，也不会让接口 500。</p>
     *
     * @param sessions 候选会话（通常是 {@code listMine} 里已经查出来的那一批，不额外查库）
     */
    public Map<Long, Long> unreadCounts(Long userId, List<ChatSession> sessions) {
        if (!active() || userId == null || sessions == null || sessions.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<Long>> sessionIdsByConvKey = new LinkedHashMap<>();
        for (ChatSession session : sessions) {
            String convKey = convKeyOf(userId, session);
            if (convKey == null) {
                continue;
            }
            sessionIdsByConvKey.computeIfAbsent(convKey, key -> new ArrayList<>()).add(session.getId());
        }
        if (sessionIdsByConvKey.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Long> byConvKey = queryUnreadCountsByConvKey(userId, sessionIdsByConvKey.keySet());
            Map<Long, Long> result = new HashMap<>();
            for (Map.Entry<String, List<Long>> entry : sessionIdsByConvKey.entrySet()) {
                Long count = byConvKey.get(entry.getKey());
                if (count == null) {
                    continue;
                }
                readHits.add(entry.getValue().size());
                for (Long sessionId : entry.getValue()) {
                    result.put(sessionId, count);
                }
            }
            // 注意：result 为空是合法结果（该用户的会话全部已读），不是失败 ——
            // 失败走下面的 catch，返回空 Map 让调用方逐会话回退到 kean 的计数器。
            return result;
        } catch (Exception ex) {
            readFallbacks.increment();
            warnOnce("批量统计未读", "userId=" + userId + ", 会话数=" + sessionIdsByConvKey.size(), ex);
            return Collections.emptyMap();
        }
    }

    /**
     * 批量：{@code conv_key → 该会话里我的未读数}（供 {@link ImMessageMirrorService} 的比对器使用）。
     *
     * <p>与 {@link #unreadCounts} 是同一套 SQL 的两个入口：一个按会话 id 回填给接口，
     * 一个按会话键回填给比对器（比对器手上只有 {@code conv_key}）。</p>
     *
     * @return 查到的会话键 → 未读数；<b>查询失败时返回 {@code null}</b>（与「查到了但全为 0」区分开：
     *         后者返回空 Map）
     */
    public Map<String, Long> unreadCountsByConvKey(Long userId, Set<String> convKeys) {
        if (!active() || userId == null || convKeys == null || convKeys.isEmpty()) {
            return null;
        }
        try {
            Map<String, Long> counts = queryUnreadCountsByConvKey(userId, convKeys);
            readHits.add(convKeys.size());
            return counts;
        } catch (Exception ex) {
            readFallbacks.increment();
            warnOnce("统计未读（供比对器）", "userId=" + userId + ", 会话数=" + convKeys.size(), ex);
            return null;
        }
    }

    /**
     * 真正发一条统计 SQL（<b>会外抛</b>，由上面的公开方法统一兜住）。
     *
     * <p>SQL 形状说明：{@code GROUP BY conv_key} 让「批量」永远只有 <b>1 条</b> SQL
     * （而不是每个会话一条）。{@code recv_id} 与 {@code conv_key} 都带条件，
     * 理由见类注释「统计维度」。</p>
     */
    private Map<String, Long> queryUnreadCountsByConvKey(Long userId, Set<String> convKeys) {
        Map<String, Long> counts = new HashMap<>();
        List<String> keys = new ArrayList<>(convKeys);
        int chunkSize = Math.min(CONV_KEY_CHUNK, Math.max(keys.size(), 1));
        for (int from = 0; from < keys.size(); from += chunkSize) {
            int to = Math.min(from + chunkSize, keys.size());
            List<String> chunk = keys.subList(from, to);
            String sql = "SELECT conv_key, COUNT(*) AS cnt FROM " + TABLE
                    + " WHERE recv_id = ? AND " + UNREAD_PREDICATE
                    + " AND conv_key IN (" + placeholders(chunk.size()) + ")"
                    + " GROUP BY conv_key";
            List<Object> args = new ArrayList<>(chunk.size() + 1);
            args.add(userId);
            args.addAll(chunk);
            for (Map<String, Object> row : boxJdbc.queryForList(sql, args.toArray(new Object[0]))) {
                Object key = row.get("conv_key");
                Object cnt = row.get("cnt");
                if (key != null && cnt != null) {
                    counts.put(key.toString(), Long.valueOf(cnt.toString()));
                }
            }
        }
        return counts;
    }

    /** 单个会话的未读数（{@code COUNT(*)}，一定会返回 0 而不是 null）。 */
    private Long queryUnreadCount(Long userId, String convKey) {
        String sql = "SELECT COUNT(*) FROM " + TABLE
                + " WHERE recv_id = ? AND conv_key = ? AND " + UNREAD_PREDICATE;
        Long count = boxJdbc.queryForObject(sql, Long.class, userId, convKey);
        return count == null ? 0L : count;
    }

    // ------------------------------------------------------------------
    // ② 写：已读单向投影到 box（独立事务，失败不影响 kean）
    // ------------------------------------------------------------------

    /**
     * 把「该会话中发给我的、{@code seq_no <= maxSeq}」的消息在 <b>box</b> 里置为已读
     * （{@code status = 3}）。
     *
     * <p>⚠️ <b>本方法只写 box。</b>kean 侧的位点、{@code chat_message.status=3}、
     * {@code a_unread/b_unread} 一律由 {@code ChatServiceImpl.markRead} 在<b>本方法之后</b>照旧维护 ——
     * 即使本方法返回 {@code false}（box 写失败）那些写也照做，这就是「回退数据保持热」的落点。</p>
     *
     * <p>为什么顺序是「先 box 后 kean」：box 是权威（原则 1），先写它能让
     * 「权威侧已经推进、投影侧还没跟上」这一种中间态只出现在<b>可自愈</b>的方向上
     * （下一次 markRead 会重放同一条 UPDATE，幂等）。反过来先写 kean 会在
     * box 写失败时留下「kean 已读、box 未读」的分叉，而那个分叉会让用户看到
     * 「角标清不掉」——正是本轮要消灭的现象。</p>
     *
     * <p>SQL 的边界（三条，逐条都有理由）：</p>
     * <ol>
     *   <li>{@code recv_id = 我} —— 只动「发给我的」，绝不把对方的消息标成已读；</li>
     *   <li>{@code conv_key = 会话键} —— 把范围锁死在本会话（{@code recv_id} 一个条件
     *       会跨会话，必须再按会话收敛）；</li>
     *   <li>{@code seq_no <= maxSeq} 与 {@code status < 3} —— 前者是「已读到哪」，
     *       后者保证<b>幂等且不倒退</b>：已读的行不会被重复 UPDATE，撤回（{@code status=2}）
     *       与待推送（{@code status=0}）的行都会被本次一起标成 3。</li>
     * </ol>
     * <p>⚠️ {@code seq_no} 在 box 是 {@code int NOT NULL}，而 kean 的 {@code seq_no} 是
     * {@code bigint}：这里用 {@code <= long} 比较，由 MySQL 做隐式比较（不会溢出；
     * 阶段 B 的 {@code seq_no} 是 int 范围内的会话内序号）。</p>
     *
     * @param userId  读消息的人（= 收件人）
     * @param session 会话（必须有 {@code id / user_a_id / user_b_id}）
     * @param maxSeq  已读到的会话内序号
     * @return {@code true} = 至少执行成功了一条 SQL（<b>0 行受影响也算 true</b>：
     *         没有需要改的行 = 本来就是已读，是正常结果）；
     *         {@code false} = 开关未开 / 数据源不可用 / SQL 失败（调用方<b>照旧</b>写 kean）
     */
    public boolean markReadInBox(Long userId, ChatSession session, Long maxSeq) {
        if (!active() || userId == null || session == null || maxSeq == null) {
            return false;
        }
        String convKey = convKeyOf(userId, session);
        if (convKey == null) {
            return false;
        }
        long cursor = Math.max(maxSeq, 0L);
        readPushes.increment();
        try {
            String sql = "UPDATE " + TABLE
                    + " SET status = " + BOX_STATUS_READED
                    + " WHERE recv_id = ? AND conv_key = ? AND seq_no <= ? AND " + UNREAD_PREDICATE;
            int rows = inBoxTransaction(sql, userId, convKey, cursor);
            readWrites.increment();
            if (rows > 0) {
                readRows.add(rows);
            }
            log.debug("[IM 未读来源] box 已读回写：userId={}, sessionId={}, convKey={}, maxSeq={}, 影响 {} 行",
                    userId, session.getId(), convKey, cursor, rows);
            return true;
        } catch (Exception ex) {
            readWriteFailures.increment();
            // ⚠️ 这里【绝不】外抛：调用方（ChatServiceImpl.markRead）可能正处在 @Transactional 里，
            //    异常一旦穿出去就会把 kean 的已读事务一起回滚掉 —— 那正是本轮要避免的。
            log.warn("[IM 未读来源] box 已读回写失败（kean 侧照旧写入，不影响用户已读操作）："
                            + "userId={}, sessionId={}, convKey={}, maxSeq={}：{}",
                    userId, session.getId(), convKey, cursor, ex.getMessage());
            return false;
        }
    }

    /**
     * 在 {@code REQUIRES_NEW} 的独立事务里执行那条 UPDATE（失败时只回滚它自己）。
     *
     * <p>与 {@link ImMessageMirrorService} 的同名实现完全同源：{@code JdbcTemplate} 用主 DataSource
     * 取连接，而 Spring 的 {@code DataSourceUtils} 会优先复用<b>当前线程已绑定</b>的连接 ——
     * 不显式挂起外层事务的话，这条 box 的 UPDATE 会跑进
     * {@code ChatServiceImpl.markRead} 的 kean 事务（同一个连接、同一个事务），
     * 那时一个真正的 SQL 错误（表不存在、权限不足……）仍可能把整个事务标记成
     * {@code rollbackOnly}，用户照样会「点了已读却报错」。</p>
     */
    private int inBoxTransaction(String sql, Object... args) {
        TransactionTemplate template = boxTemplate;
        if (template == null) {
            return boxJdbc.update(sql, args);
        }
        Integer rows = template.execute(status -> boxJdbc.update(sql, args));
        return rows == null ? 0 : rows;
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /**
     * 会话键：<b>不信任</b> {@code chat_session} 两列的排序，统一按
     * {@code min + "_" + max} 归一化（与 box 的 {@code ConvUtil.buildConvKey} 同源）。
     *
     * <p>kean 的 {@code chat_session} 本来就保证 {@code user_a_id < user_b_id}
     * （{@code findSession} 用的是 min/max），这里再归一化一次是纵深防御：
     * 万一历史行反了，查询会静默返回 0 未读，那是最难查的一类 bug。</p>
     *
     * @return {@code null} = 该用户不是这个会话的参与者，或会话两端有 NULL（调用方应回退）
     */
    static String convKeyOf(Long userId, ChatSession session) {
        Long a = session.getUserAId();
        Long b = session.getUserBId();
        if (userId == null || a == null || b == null) {
            return null;
        }
        if (!userId.equals(a) && !userId.equals(b)) {
            return null;
        }
        return ImMessageMirrorService.describeConvKeyForLog(a, b);
    }

    /** 生成 {@code ?,?,?} 形式的占位符串（列数由调用方保证与参数个数一致）。 */
    private static String placeholders(int count) {
        StringBuilder sb = new StringBuilder(count * 2);
        for (int i = 0; i < count; i++) {
            sb.append(i == 0 ? "?" : ",?");
        }
        return sb.toString();
    }

    /** 首次失败打 WARN（含上下文与异常 message），之后降为 DEBUG —— 见 {@link #failureWarned}。 */
    private void warnOnce(String what, String context, Exception ex) {
        if (failureWarned.compareAndSet(false, true)) {
            log.warn("[IM 未读来源] box 未读{}失败，本会话/本次已回退到 kean 的既有算法"
                            + "（接口不受影响；同一原因只提示这一次，之后为 DEBUG）：{}：{}",
                    what, context, ex.getMessage());
        } else {
            log.debug("[IM 未读来源] box 未读{}失败（已回退到 kean）：{}：{}", what, context, ex.getMessage());
        }
    }

    /**
     * 解析 box 的访问通道 —— <b>复用主 DataSource</b>（与
     * {@link ImShadowUserService#resolveJdbcTemplate} / {@link ImMessageMirrorService} 完全同源）。
     *
     * <p>⚠️ 注意这里<b>没有</b>「开关关了就不解析」的短路：未读查询可能在任何时刻被切到 box，
     * 而这个 JdbcTemplate 只是<b>持有引用</b>（不建连接、不发 SQL），代价为零。
     * <b>绝不新建 DataSource</b>（不引入第二个连接池，也不动任何数据源配置）。</p>
     */
    private static JdbcTemplate resolveJdbcTemplate(ObjectProvider<DataSource> provider) {
        try {
            DataSource dataSource = provider.getIfAvailable();
            if (dataSource == null) {
                log.warn("[IM 未读来源] 容器里没有可用的 DataSource，未读查询将始终回退到 kean（不影响启动）");
                return null;
            }
            return new JdbcTemplate(dataSource);
        } catch (Exception ex) {
            log.warn("[IM 未读来源] 解析数据源失败（不影响启动，未读查询将回退到 kean）：{}", ex.getMessage());
            return null;
        }
    }

    /** 构造 {@code REQUIRES_NEW} 的事务模板（理由见 {@link #inBoxTransaction}）。 */
    private static TransactionTemplate resolveTransactionTemplate(
            ObjectProvider<PlatformTransactionManager> provider) {
        try {
            PlatformTransactionManager transactionManager = provider.getIfAvailable();
            if (transactionManager == null) {
                log.warn("[IM 未读来源] 容器里没有 PlatformTransactionManager：box 的已读回写将退化为"
                        + "「与 kean 主事务共用连接」（异常仍被吞掉，但少了一层事务隔离）");
                return null;
            }
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            // ⭐ 这一行就是「box 的写失败绝不回滚 kean 的已读」的机制：挂起外层事务、另开一个连接与事务。
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return template;
        } catch (Exception ex) {
            log.warn("[IM 未读来源] 解析事务管理器失败（不影响启动，退化为共用连接）：{}", ex.getMessage());
            return null;
        }
    }
}
