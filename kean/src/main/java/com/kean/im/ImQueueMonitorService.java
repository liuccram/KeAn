package com.kean.im;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * IM 队列巡检（第 ② 项）：定期看「镜像投递的队列有没有堆起来 / 有没有再也无人消费的残留」，
 * 超阈值就<b>报警</b>（邮件 + ERROR 日志），并把结果供 {@code /health/ready} 的 {@code im} 字段
 * 与 {@code GET /api/admin/im/stats} 读取。
 *
 * <h2>为什么需要它</h2>
 * <p>IM 的投递是<b>尽力而为的旁路</b>：{@link ImSenderService} 刻意吞掉异常
 * （见该类注释 —— 上抛会回滚已经成功的消息写入）。所以「Redis 挂了 / 队列堆到几万条 /
 * 投递一直在失败」在业务日志里只是一行行 {@code log.warn}，<b>没有任何人会知道</b>。
 * 本类就是那个「会知道」的东西。</p>
 *
 * <h2>它做什么（只读 + 告警，绝不改数据）</h2>
 * <ol>
 *   <li>用 <b>{@code SCAN}</b> 枚举 {@code im:message:private:*} 与 {@code im:message:system:*}，
 *       对每个键取 {@code LLEN}（{@link StringRedisTemplate#opsForList()}{@code .size(key)}）；
 *       <b>绝对不用 {@code KEYS}</b> —— {@code KEYS} 是 O(N) 全键遍历且会阻塞 Redis 的单线程，
 *       在大库上会卡住所有客户端（包括 im-server 的消息拉取），线上禁用。
 *       {@code SCAN} 是游标式增量遍历，每次只返回一批，代价可控；</li>
 *   <li><b>旧 serverId 残留检测</b>（只报告，不自动删）：im-server 每次启动都会从
 *       {@code im:max_server_id} 自增拿一个新的 {@code serverId}，队列键是
 *       {@code im:message:*:{serverId}}。于是<b>旧 serverId 的队列再也不会有人消费</b>
 *       （见 {@code docs/ops/im-server-patch.md} §6「多实例 / 频繁重启的 serverId 漂移」），
 *       消息静静躺在 Redis 里直到把内存吃满。判据两条（命中任一即残留）：
 *       <ul>
 *         <li>{@code serverId > im:max_server_id} —— 非法值，或计数器被重置过；</li>
 *         <li>队列长度 &gt; 0 且<b>连续 {@code kean.im.residue-stagnant-rounds}（默认 5）轮长度完全没变</b>
 *             —— 没有消费者在拉它（正常消费下长度会持续波动）。</li>
 *       </ul></li>
 *   <li>把整轮结果打包成一个<b>不可变</b>的 {@link ImQueueInspection} 发布出去
 *       （状态在产出时算好），让 {@code /health/ready} 与管理端接口<b>读同一个对象</b>
 *       —— 这是「同源同判据」的落实方式，两个消费方谁都不再各自重算状态。</li>
 * </ol>
 *
 * <h2>Redis 不可用时</h2>
 * <p>巡检本轮标记 {@code redisAvailable=false} + 一句说明，并<b>保留上一轮的状态</b>
 * （不臆造 {@code UP}，也不凭空升成 {@code DEGRADED}）。但「投递失败次数」
 * 来自 JVM 内存计数器、不依赖 Redis，所以它照常判定 —— Redis 挂了且真有消息在投递时，
 * 仍然会 ERROR + 邮件告警。管理端 GET 接口因此永远打得开（返回「数据不可用」而不是 500）。</p>
 *
 * <h2>阈值（全部有默认值，可配；<b>刻意不写进 application*.yml</b>，与项目风格一致）</h2>
 * <table border="1">
 *   <caption>配置项</caption>
 *   <tr><th>配置项</th><th>默认</th><th>含义 / 动作</th></tr>
 *   <tr><td>{@code kean.im.monitor-interval-ms}</td><td>60000</td><td>巡检周期（固定延迟，毫秒）</td></tr>
 *   <tr><td>{@code kean.im.queue-warn-threshold}</td><td>1000</td><td>单队列长度 &gt; 此值 → WARN 日志，状态 DEGRADED</td></tr>
 *   <tr><td>{@code kean.im.queue-error-threshold}</td><td>5000</td><td>单队列长度 &gt; 此值 → ERROR 日志 + 邮件告警</td></tr>
 *   <tr><td>{@code kean.im.delivery-failure-threshold}</td><td>20</td>
 *       <td>最近一个巡检周期内投递失败增量 &gt; 此值 → ERROR 日志 + 邮件告警</td></tr>
 *   <tr><td>{@code kean.im.residue-stagnant-rounds}</td><td>5</td><td>长度连续多少轮不变才算「长期无消费」</td></tr>
 *   <tr><td>{@code kean.im.scan-count}</td><td>200</td><td>{@code SCAN} 每批建议条数（COUNT 只是提示，不是上限）</td></tr>
 *   <tr><td>{@code kean.im.scan-max-keys}</td><td>2000</td><td>单轮单前缀最多检查多少个键（防御性封顶）</td></tr>
 * </table>
 *
 * <h2>IM 未启用时：整个巡检直接跳过、静默</h2>
 * <p>{@link ImSenderService#enabled()} 为 {@code false}（{@code IM_JWT_SECRET} 未配置 /
 * 不足 32 字节 / {@code KEAN_IM_MIRROR_ENABLED=false}）时：本轮<b>不连 Redis、不判残留、不告警</b>，
 * 状态置为 {@link #STATUS_DISABLED}，并且<b>只在「启用 → 未启用」的第一次记一行 DEBUG</b>
 * （用 {@link AtomicBoolean} 去重），避免每 60 秒刷一行日志。</p>
 *
 * <h2>多实例</h2>
 * <p>加 {@code @SchedulerLock}（与 {@code TaskScheduleService} 同一套 JdbcTemplate 锁）：
 * 同一时刻只有一个实例真正巡检，避免多实例重复发告警邮件。拿不到锁的实例本轮直接跳过。</p>
 *
 * <h2>唯一的写操作：无</h2>
 * <p>本类只发 {@code SCAN} / {@code GET} / {@code LLEN} 三类<b>只读</b>命令，
 * <b>从不</b> {@code DEL} / {@code LTRIM} / {@code SET}。残留队列的清理命令只出现在告警正文里，
 * 由运维人工执行，或者走管理端那条<b>显式确认 + 二次校验</b>的 POST 接口
 * （{@code POST /api/admin/im/queues/clean}）。</p>
 */
@Component
public class ImQueueMonitorService {

    private static final Logger log = LoggerFactory.getLogger(ImQueueMonitorService.class);

    /** box-im {@code IMRedisKey.IM_MESSAGE_PRIVATE_QUEUE}（真实键带 {@code :{serverId}} 后缀）。 */
    private static final String PRIVATE_QUEUE_PREFIX = "im:message:private";

    /** box-im {@code IMRedisKey.IM_MESSAGE_SYSTEM_QUEUE}。 */
    private static final String SYSTEM_QUEUE_PREFIX = "im:message:system";

    /** box-im {@code IMRedisKey.IM_USER_SERVER_ID = "im:user:server_id"}，完整键 {@code ...:{userId}:{terminal}}。 */
    private static final String USER_SERVER_ID_PREFIX = "im:user:server_id";

    /** box-im {@code IMServerGroup} 启动时自增的活跃 serverId 上界。 */
    private static final String MAX_SERVER_ID_KEY = "im:max_server_id";

    /** {@code /health/ready} 的 {@code im} 取值 ①：密钥未配置 / 不合格 / 镜像投递已关闭。 */
    public static final String STATUS_DISABLED = "DISABLED";

    /** {@code /health/ready} 的 {@code im} 取值 ②：启用且最近一次巡检无异常。 */
    public static final String STATUS_UP = "UP";

    /** {@code /health/ready} 的 {@code im} 取值 ③：启用但有队列堆积 / 投递失败超阈值 / 残留队列。 */
    public static final String STATUS_DEGRADED = "DEGRADED";

    /** 校验活跃 serverId 集合时，{@code MGET} 的分批大小（避免一次拉太多槽位值）。 */
    private static final int ACTIVE_SET_BATCH_SIZE = 200;

    private final StringRedisTemplate redis;
    private final ImSenderService imSenderService;
    private final ImAlertService imAlertService;

    private final long queueWarnThreshold;
    private final long queueErrorThreshold;
    private final long failureErrorThreshold;
    private final int stagnantRounds;
    private final int scanCount;
    private final int scanMaxKeys;

    /** 巡检周期（毫秒）。只用于日志与告警正文的展示；真正的调度由 {@link #inspect()} 上的占位符决定。 */
    private final long intervalMs;

    /**
     * 最近一次巡检结果（不可变对象，整体替换发布）。
     *
     * <p>初值 {@code null} = 「还没巡检过」：此时 {@link #healthStatus()} 返回 {@code UP}
     * —— IM 已启用但尚无异常记录，不能因为「还没测过」就把状态判成异常（IM 本身是旁路）。</p>
     */
    private volatile ImQueueInspection lastInspection;

    /** 上一轮读到的投递计数快照（用于算「本周期失败增量」）。只被巡检线程访问。 */
    private ImDeliveryStats lastStats = ImDeliveryStats.zero();

    /**
     * 上一轮各队列的长度，用于识别「长期无消费」的残留队列。只被巡检线程访问；
     * 每轮结束会剔除本轮未出现的键，避免内存无界增长。
     */
    private final Map<String, Long> lastLengths = new HashMap<>();

    /** 各队列「长度与上一轮相同」的连续轮数。只被巡检线程访问。 */
    private final Map<String, Integer> stagnant = new HashMap<>();

    /** 保证「IM 未启用」的 DEBUG 日志只打一次（重新启用后可再次打印）。 */
    private final AtomicBoolean disabledLogged = new AtomicBoolean(false);

    public ImQueueMonitorService(
            StringRedisTemplate redis,
            ImSenderService imSenderService,
            ImAlertService imAlertService,
            @Value("${kean.im.monitor-interval-ms:60000}") long intervalMs,
            @Value("${kean.im.queue-warn-threshold:1000}") long queueWarnThreshold,
            @Value("${kean.im.queue-error-threshold:5000}") long queueErrorThreshold,
            @Value("${kean.im.delivery-failure-threshold:20}") long failureErrorThreshold,
            @Value("${kean.im.residue-stagnant-rounds:5}") int stagnantRounds,
            @Value("${kean.im.scan-count:200}") int scanCount,
            @Value("${kean.im.scan-max-keys:2000}") int scanMaxKeys
    ) {
        this.redis = redis;
        this.imSenderService = imSenderService;
        this.imAlertService = imAlertService;
        this.intervalMs = intervalMs;
        this.queueWarnThreshold = queueWarnThreshold;
        this.queueErrorThreshold = queueErrorThreshold;
        this.failureErrorThreshold = failureErrorThreshold;
        this.stagnantRounds = Math.max(stagnantRounds, 1);
        this.scanCount = Math.max(scanCount, 1);
        this.scanMaxKeys = Math.max(scanMaxKeys, 1);
        log.info("[IM 巡检] 已装载：周期={}ms，队列 WARN>{} / ERROR>{}，周期内投递失败 ERROR>{}，"
                        + "残留判定=长度连续 {} 轮不变（配置项均在 kean.im.* 下，有默认值，无需写进 application*.yml）",
                intervalMs, queueWarnThreshold, queueErrorThreshold,
                failureErrorThreshold, this.stagnantRounds);
    }

    /**
     * IM 总体状态 —— {@code /health/ready} 的 {@code im} 字段与管理端看板
     * <b>共用这一个方法</b>（同源同判据的唯一入口）。
     *
     * <p>只读 volatile 字段，不做任何 I/O，因此探针调它不会变慢、管理端也不会因 Redis 抖动而失败。</p>
     *
     * @return {@link #STATUS_DISABLED} / {@link #STATUS_UP} / {@link #STATUS_DEGRADED} 之一
     */
    public String healthStatus() {
        if (!imSenderService.enabled()) {
            return STATUS_DISABLED;
        }
        ImQueueInspection inspection = lastInspection;
        return inspection == null ? STATUS_UP : inspection.status();
    }

    /**
     * 最近一次巡检的<b>完整结果</b>（管理端看板用；与 {@link #healthStatus()} 同源）。
     *
     * @return 从未巡检过时返回 {@code null}（管理端据此显示「尚未完成第一次巡检」）
     */
    public ImQueueInspection lastInspection() {
        return lastInspection;
    }

    /**
     * 最近一次<b>真正执行过</b>的巡检时间；IM 未启用（整轮跳过）或从未巡检时为 {@code null}。
     */
    public LocalDateTime lastInspectionAt() {
        ImQueueInspection inspection = lastInspection;
        return inspection == null ? null : inspection.inspectedAt();
    }

    /**
     * 当前活跃的 im-server serverId 集合 = <b>用户在线槽位值 ∪ { im:max_server_id }</b>。
     *
     * <p><b>只被管理端「清理残留队列」在删除前调用</b>（不在 60 秒巡检周期里跑）：
     * 删队列是危险操作，必须先确认「这个 serverId 现在没有任何用户挂着、也不是最新实例」。</p>
     *
     * <p>读两次 Redis：① {@code GET im:max_server_id}；② <b>SCAN</b> {@code im:user:server_id:*}
     * 并分批 {@code MGET} 取值（<b>不用 KEYS</b>）。
     * 槽位键数量受 {@code kean.im.scan-max-keys} 封顶，若达到封顶则
     * {@link ActiveServerIds#complete()} 为 {@code false} —— 调用方<b>必须拒绝删除</b>，
     * 因为「活跃集合不完整」意味着可能误删还在用的队列。</p>
     *
     * @throws IllegalStateException Redis 不可用（由调用方转成「数据不可用」，不要 500）
     */
    public ActiveServerIds liveServerIds() {
        Set<Long> ids = new LinkedHashSet<>();
        Long maxServerId;
        try {
            maxServerId = readMaxServerId();
        } catch (Exception ex) {
            throw new IllegalStateException("读取 " + MAX_SERVER_ID_KEY + " 失败：" + ex.getMessage(), ex);
        }
        if (maxServerId != null) {
            // 最新实例（编号最大的那个）最可能正在运行，永远不允许被当成残留删掉。
            ids.add(maxServerId);
        }
        List<String> slotKeys;
        try {
            slotKeys = scanKeys(USER_SERVER_ID_PREFIX + ":*");
        } catch (Exception ex) {
            throw new IllegalStateException("SCAN " + USER_SERVER_ID_PREFIX + ":* 失败：" + ex.getMessage(), ex);
        }
        boolean complete = slotKeys.size() < scanMaxKeys;
        for (int i = 0; i < slotKeys.size(); i += ACTIVE_SET_BATCH_SIZE) {
            List<String> chunk = slotKeys.subList(i, Math.min(i + ACTIVE_SET_BATCH_SIZE, slotKeys.size()));
            List<String> values;
            try {
                values = redis.opsForValue().multiGet(chunk);
            } catch (Exception ex) {
                throw new IllegalStateException("读取在线槽位值失败：" + ex.getMessage(), ex);
            }
            if (values == null) {
                continue;
            }
            for (String value : values) {
                Long parsed = parseServerId(value);
                if (parsed != null) {
                    ids.add(parsed);
                }
            }
        }
        return new ActiveServerIds(Set.copyOf(ids), complete, maxServerId);
    }

    /**
     * 巡检一轮。
     *
     * <p>周期由 {@code kean.im.monitor-interval-ms} 决定（默认 60000ms = 60 秒）。
     * {@code @Scheduled} 的注解值必须是编译期常量，所以这里用<b>占位符 + 默认值</b>表达
     * —— 与 {@code @Value("${...:默认值}")} 等价，同样<b>不需要写进 application*.yml</b>。</p>
     *
     * <p>用 {@code fixedDelay}（上一轮结束后再等 60 秒）而不是 {@code fixedRate}：
     * 巡检耗时取决于键数量，fixedDelay 能让慢轮次自然错开，不会堆积执行。</p>
     *
     * <p>整个方法体包着 try/catch：巡检是旁路，任何异常都只记日志，绝不影响业务线程。</p>
     */
    @Scheduled(fixedDelayString = "${kean.im.monitor-interval-ms:60000}")
    @SchedulerLock(name = "im.queueMonitor", lockAtMostFor = "PT2M")
    public void inspect() {
        try {
            if (!imSenderService.enabled()) {
                markDisabled();
                return;
            }
            disabledLogged.set(false);
            runRound();
        } catch (Exception ex) {
            // 连巡检自身出错也不能上抛：它是 @Scheduled 线程，抛出去只会污染调度日志。
            log.warn("[IM 巡检] 本轮巡检异常（IM 是旁路，不影响任何业务），下一轮重试：{}", ex.getMessage());
        }
    }

    /** IM 未启用：状态置 DISABLED，清掉残留判定状态，只记一次 DEBUG。 */
    private void markDisabled() {
        lastInspection = ImQueueInspection.disabled(
                "IM 未启用（IM_JWT_SECRET 未配置/不合格，或 KEAN_IM_MIRROR_ENABLED=false）：本轮及后续巡检全部跳过");
        lastLengths.clear();
        stagnant.clear();
        if (disabledLogged.compareAndSet(false, true)) {
            log.debug("[IM 巡检] IM 未启用：本轮及后续巡检全部跳过 —— 不读 Redis、不判残留、不告警");
        }
    }

    private void runRound() {
        LocalDateTime now = LocalDateTime.now();
        ImDeliveryStats current = imSenderService.snapshot();
        ImDeliveryStats delta = current.since(lastStats);
        lastStats = current;
        long failedDelta = delta.failed();

        boolean redisOk = true;
        String message;
        Long maxServerId = null;
        List<ImQueueInspection.QueueStat> queues = new ArrayList<>();
        List<String> residueKeys = new ArrayList<>();
        List<String> residueReasons = new ArrayList<>();

        try {
            maxServerId = readMaxServerId();
        } catch (Exception ex) {
            redisOk = false;
            log.warn("[IM 巡检] 读取 {} 失败（本轮 Redis 数据不可用）：{}", MAX_SERVER_ID_KEY, ex.getMessage());
        }
        if (redisOk) {
            try {
                inspectPrefix(PRIVATE_QUEUE_PREFIX, maxServerId, queues, residueKeys, residueReasons);
                inspectPrefix(SYSTEM_QUEUE_PREFIX, maxServerId, queues, residueKeys, residueReasons);
            } catch (Exception ex) {
                // SCAN 都发不出去 = Redis 不可用（单键 LLEN 失败在 inspectPrefix 内已单独兜住，不会走到这里）。
                redisOk = false;
                queues.clear();
                residueKeys.clear();
                residueReasons.clear();
                log.warn("[IM 巡检] Redis 读取失败，本轮队列数据不可用（不臆造状态，保留上一轮结论）：{}", ex.getMessage());
            }
        }

        // 只保留本轮仍存在的键，避免长跑后 Map 无界增长。
        lastLengths.keySet().retainAll(queues.stream().map(ImQueueInspection.QueueStat::key).toList());
        stagnant.keySet().retainAll(queues.stream().map(ImQueueInspection.QueueStat::key).toList());

        List<ImQueueInspection.QueueStat> warnStats = new ArrayList<>();
        List<ImQueueInspection.QueueStat> errorStats = new ArrayList<>();
        for (ImQueueInspection.QueueStat stat : queues) {
            if (stat.length() > queueErrorThreshold) {
                errorStats.add(stat);
            } else if (stat.length() > queueWarnThreshold) {
                warnStats.add(stat);
            }
        }

        boolean degraded;
        if (redisOk) {
            degraded = false;
            // 结论要在下面所有检查（队列/残留/投递失败）跑完之后回填。
            message = null;
        } else {
            // 不臆造：Redis 读不到时保留上一轮的状态结论（之前是 DEGRADED 就还是 DEGRADED）。
            ImQueueInspection previous = lastInspection;
            degraded = previous != null && STATUS_DEGRADED.equals(previous.status());
            message = "Redis 不可用，队列与残留数据不可用（状态沿用上一轮结论）";
        }

        if (redisOk) {
            if (!warnStats.isEmpty()) {
                degraded = true;
                log.warn("[IM 巡检] 队列长度超过 WARN 阈值 {}：{}（队列在堆，尚未到 ERROR 阈值 {}）",
                        queueWarnThreshold, display(warnStats), queueErrorThreshold);
            }
            if (!errorStats.isEmpty()) {
                degraded = true;
                log.error("[IM 巡检] 队列长度超过 ERROR 阈值 {}：{}（im-server 可能没在消费或已停）",
                        queueErrorThreshold, display(errorStats));
                imAlertService.alert(
                        ImAlertService.KIND_QUEUE_BACKLOG,
                        "ERROR",
                        "镜像投递队列长度超过 ERROR 阈值（im-server 消费不动或已停）",
                        "超过阈值的队列：" + display(errorStats),
                        "ERROR > " + queueErrorThreshold + " 条（kean.im.queue-error-threshold）",
                        keysOf(errorStats),
                        "【课安 IM 告警】队列堆积（ERROR）",
                        backlogBody(errorStats));
            }
            if (!residueKeys.isEmpty()) {
                degraded = true;
                log.warn("[IM 巡检] 发现 {} 个疑似残留队列（旧 serverId，无人消费；只报告不自动删）：{}",
                        residueKeys.size(), residueReasons);
                imAlertService.alert(
                        ImAlertService.KIND_QUEUE_RESIDUE,
                        "WARN",
                        "疑似残留队列（旧 serverId，无人消费）",
                        String.join("；", residueReasons),
                        "长度 > 0 且连续 " + stagnantRounds + " 轮不变，或 serverId > im:max_server_id"
                                + "（kean.im.residue-stagnant-rounds）",
                        List.copyOf(residueKeys),
                        "【课安 IM 告警】疑似残留队列（WARN）",
                        residueBody(residueKeys, residueReasons, maxServerId));
            }
        }
        // 投递失败判定与 Redis 无关（计数在 JVM 内存里），所以 Redis 挂了也要照常报 —— 这正是 Redis 故障的信号。
        if (failedDelta > failureErrorThreshold) {
            degraded = true;
            log.error("[IM 巡检] 最近一个巡检周期内投递失败 {} 次，超过阈值 {}（累计失败 {}，累计尝试 {}）",
                    failedDelta, failureErrorThreshold, current.failed(), current.attempts());
            imAlertService.alert(
                    ImAlertService.KIND_DELIVERY_FAILURE,
                    "ERROR",
                    "最近一个巡检周期内投递失败次数超过阈值（通常是 Redis 不可用 / 写入超时）",
                    "本周期失败 " + failedDelta + " 次（累计失败 " + current.failed() + " 次）",
                    "周期内失败 > " + failureErrorThreshold + " 次（kean.im.delivery-failure-threshold）",
                    List.of(PRIVATE_QUEUE_PREFIX + ":{serverId}", SYSTEM_QUEUE_PREFIX + ":{serverId}", MAX_SERVER_ID_KEY),
                    "【课安 IM 告警】投递失败次数超阈值（ERROR）",
                    failureBody(delta, current));
        }

        if (redisOk) {
            message = degraded
                    ? "本轮巡检发现异常：队列堆积 / 残留队列 / 投递失败超阈值（详见下方数据与告警历史）"
                    : "本轮巡检正常：无队列堆积、无残留队列，周期内投递失败未超阈值";
        }

        lastInspection = new ImQueueInspection(
                degraded ? STATUS_DEGRADED : STATUS_UP,
                redisOk,
                message,
                List.copyOf(queues),
                List.copyOf(warnStats),
                List.copyOf(errorStats),
                maxServerId,
                failedDelta,
                now);

        // 每轮摘要只进 DEBUG：prod 的 root 日志级别是 warn，INFO 每分钟一行会变成噪音。
        if (log.isDebugEnabled()) {
            log.debug("[IM 巡检] 本轮完成：状态={} redis数据可用={}，检查队列 {} 个（最大长度 {}），"
                            + "本周期投递 尝试={} 成功={} 失败={} IM未启用跳过={}",
                    lastInspection.status(), redisOk, queues.size(), maxLength(queues),
                    delta.attempts(), delta.pushed(), delta.failed(), delta.skipped());
        }
    }

    /**
     * 枚举一个前缀下的所有队列键，取长度并判定残留；结果追加进入参集合。
     *
     * <p><b>SCAN 失败会往上抛</b>（Redis 不可用的信号，由 {@link #runRound()} 统一处理）；
     * 单个键 {@code LLEN} 失败只记 WARN 并跳过（部分数据比没有数据好）。</p>
     */
    private void inspectPrefix(String prefix,
                               Long maxServerId,
                               List<ImQueueInspection.QueueStat> queues,
                               List<String> residueKeys,
                               List<String> residueReasons) {
        String pattern = prefix + ":*";
        List<String> keys = scanKeys(pattern);
        for (String key : keys) {
            long length;
            try {
                Long size = redis.opsForList().size(key);
                length = size == null ? 0L : size;
            } catch (Exception ex) {
                log.warn("[IM 巡检] 读取队列长度失败 key={}（本轮跳过该键）：{}", key, ex.getMessage());
                continue;
            }

            Long serverId = serverIdOf(key);
            String residueReason = null;
            if (serverId != null) {
                if (maxServerId != null && serverId > maxServerId) {
                    residueReason = "serverId=" + serverId + " 大于 im:max_server_id=" + maxServerId
                            + "，该编号不可能有消费者";
                } else {
                    // 「长期无消费」判据：长度 > 0 且连续 N 轮完全没变。
                    Long previous = lastLengths.get(key);
                    int rounds = 0;
                    if (length > 0 && previous != null && previous == length) {
                        Integer last = stagnant.get(key);
                        rounds = (last == null ? 0 : last) + 1;
                    }
                    stagnant.put(key, rounds);
                    if (length > 0 && rounds >= stagnantRounds) {
                        residueReason = "长度 " + length + " 已连续 " + rounds + " 轮未变化，疑似无消费者";
                    }
                }
            }
            lastLengths.put(key, length);
            boolean residue = residueReason != null;
            queues.add(new ImQueueInspection.QueueStat(key, serverId, length, residue, residueReason));
            if (residue) {
                residueKeys.add(key);
                residueReasons.add(key + "（" + residueReason + "）");
            }
        }
    }

    /**
     * 用 {@code SCAN} 游标枚举键（<b>不用 {@code KEYS}</b>）。
     *
     * <p>{@code KEYS} 的代价是 O(N) 且阻塞 Redis 单线程，键多时会把 im-server 的
     * {@code leftPop} 一起卡住；{@code SCAN} 每次只遍历一小批、游标式推进，
     * 在大库上也不会长时间占用事件循环。{@code COUNT} 只是「每次大概多少条」的提示而非上限，
     * 所以下面还要用 {@code scanMaxKeys} 做硬封顶，防止键空间被异常写入撑爆时巡检自己失控。</p>
     *
     * <p>游标用完即 {@code close()}（释放服务端游标），始终在同一个 {@code RedisCallback}
     * 里消费完，不把游标泄漏到回调之外。回调内一旦出错就转成 {@link IllegalStateException}
     * 往外抛 —— 既把「Redis 不可用」这个事实传给调用方，也顺带绕开
     * {@link Cursor#close()} 在不同 Spring Data Redis 版本上 throws 声明不一致的问题。</p>
     *
     * @throws IllegalStateException SCAN 失败（Redis 不可用）
     */
    private List<String> scanKeys(String pattern) {
        List<String> keys = redis.execute((RedisCallback<List<String>>) connection -> {
            List<String> collected = new ArrayList<>();
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(scanCount).build();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    collected.add(new String(cursor.next(), StandardCharsets.UTF_8));
                    if (collected.size() >= scanMaxKeys) {
                        log.warn("[IM 巡检] SCAN {} 命中数达到封顶 {}，本轮只处理前 {} 个；"
                                        + "若这不是预期，请人工确认键空间是否异常膨胀",
                                pattern, scanMaxKeys, scanMaxKeys);
                        break;
                    }
                }
            } catch (Exception ex) {
                throw new IllegalStateException("SCAN " + pattern + " 失败：" + ex.getMessage(), ex);
            }
            return collected;
        });
        return keys == null ? new ArrayList<>() : keys;
    }

    /**
     * 读 {@code im:max_server_id}（当前活跃 serverId 上界）。
     *
     * @return 键不存在 / 不是正整数时返回 {@code null} —— 此时只按「长期无消费」判残留，
     *         <b>不</b>因为缺这个键就把所有队列都当成越界残留
     * @throws RuntimeException Redis 不可用（由调用方判定「数据不可用」）
     */
    private Long readMaxServerId() {
        String raw = redis.opsForValue().get(MAX_SERVER_ID_KEY);
        if (!StringUtils.hasText(raw)) {
            log.debug("[IM 巡检] 未读到 {}（im-server 从未启动过？），本轮跳过「serverId 越界」判据",
                    MAX_SERVER_ID_KEY);
            return null;
        }
        Long value = parseServerId(raw);
        return value != null && value > 0 ? value : null;
    }

    /** 从 {@code im:message:private:{serverId}} 里取 serverId；没有数字后缀 / 溢出则返回 {@code null}。 */
    private static Long serverIdOf(String key) {
        int index = key.lastIndexOf(':');
        return index < 0 ? null : parseServerId(key.substring(index + 1).trim());
    }

    /** 解析 serverId 字符串（只允许纯数字、长度不超过 18 位，顺带避免 parseLong 溢出）。 */
    private static Long parseServerId(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || value.length() > 18) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return null;
            }
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static List<String> keysOf(List<ImQueueInspection.QueueStat> stats) {
        List<String> keys = new ArrayList<>(stats.size());
        for (ImQueueInspection.QueueStat stat : stats) {
            keys.add(stat.key());
        }
        return keys;
    }

    private static List<String> display(List<ImQueueInspection.QueueStat> stats) {
        List<String> entries = new ArrayList<>(stats.size());
        for (ImQueueInspection.QueueStat stat : stats) {
            entries.add(stat.key() + " 长度=" + stat.length());
        }
        return entries;
    }

    private static long maxLength(List<ImQueueInspection.QueueStat> stats) {
        long max = 0L;
        for (ImQueueInspection.QueueStat stat : stats) {
            if (stat.length() > max) {
                max = stat.length();
            }
        }
        return max;
    }

    /**
     * 活跃 serverId 集合的读取结果（见 {@link #liveServerIds()}）。
     *
     * @param ids        活跃编号集合（用户在线槽位值 ∪ 最新实例）
     * @param complete   {@code false} 表示在线槽位键数超过扫描封顶、集合<b>可能不完整</b> ——
     *                   删除操作必须因此被拒绝（宁可让运维手工处理，也不能误删还有人在用的队列）
     * @param maxServerId {@code im:max_server_id} 当前值（读不到为 {@code null}）
     */
    public record ActiveServerIds(Set<Long> ids, boolean complete, Long maxServerId) {

        /**
         * 是否可以据此安全判断「某 serverId 不是活跃的」。
         *
         * <p>两个条件缺一不可：集合完整（否则可能漏掉活跃编号），
         * 且 {@code im:max_server_id} 可读（否则连「最新实例」都确认不了）。</p>
         */
        public boolean usableForCleanup() {
            return complete && maxServerId != null;
        }
    }

    // ------------------------------------------------------------------
    // 告警正文（可操作：问题类型 / 当前值 / 阈值 / 相关键 / 建议命令）
    // ------------------------------------------------------------------

    private String backlogBody(List<ImQueueInspection.QueueStat> errorStats) {
        List<String> keys = keysOf(errorStats);
        return """
                【课安 IM 告警】队列堆积

                问题类型：镜像投递队列长度超过 ERROR 阈值（im-server 消费不动或已停）
                当前值：%s
                阈值：WARN > %d 条 / ERROR > %d 条（配置项 kean.im.queue-warn-threshold / kean.im.queue-error-threshold）
                巡检周期：%d ms（配置项 kean.im.monitor-interval-ms）

                背景：kean 只负责把消息写进 Redis 队列，消费方是 box-im 的 im-server。
                队列在堆意味着「消息确实写进去了，但没人推给客户端」。排查顺序：
                  1) im-server 进程是否在跑（systemctl status im-server）
                  2) im-server 与 kean 是否连同一个 Redis 的 0 号库（见 docs/ops/im-server-patch.md §2.3）
                  3) 这些队列键的 serverId 是否还是 im-server 的活跃编号：
                       redis-cli -n 0 GET im:max_server_id
                  4) 若该 serverId 的 im-server 已经不会再来消费（重启导致编号漂移），
                     队列会永久堆积，需要人工清理（见文末 DEL 命令）。
                     注意：只把消息从队列删掉，并不会补推给客户端。

                相关 Redis 键（基键 + :{serverId} 才是真实队列，本程序只读不删）：
                  %s

                建议的处理命令：
                %s
                说明：本邮件由 kean 定时巡检自动发出；同一类告警 %d 分钟内只发一次（冷却期内只记日志）。
                """.formatted(
                display(errorStats),
                queueWarnThreshold,
                queueErrorThreshold,
                intervalMs,
                keys,
                ImAlertService.cleanupHint(keys),
                imAlertService.cooldownMinutes());
    }

    private String failureBody(ImDeliveryStats delta, ImDeliveryStats current) {
        return """
                【课安 IM 告警】投递失败次数超阈值

                问题类型：镜像投递在最近一个巡检周期内失败次数过多（通常是 Redis 不可用 / 写入超时）
                当前值：本周期失败 %d 次（阈值 %d 次）
                本周期：尝试 %d 条消息、成功入队 %d 次、失败 %d 次、因 IM 未启用跳过 %d 次
                累计：尝试 %d 条消息、成功入队 %d 次、失败 %d 次、跳过 %d 次
                巡检周期：%d ms（配置项 kean.im.monitor-interval-ms）
                阈值配置项：kean.im.delivery-failure-threshold

                背景：kean 的镜像投递是「尽力而为」的旁路 —— 投递失败不影响业务
                （消息已落课安数据库，HTTP 链路照常返回），所以只能靠这里发现。排查顺序：
                  1) Redis 是否可用（kean 日志关键字：「[IM 镜像投递] 写入队列」）
                  2) 是否触发 Redis 内存上限导致写入被拒（redis-cli -n 0 INFO memory）
                  3) 是否只是宿主网络抖动（是的话，下一轮巡检的失败数会回落）

                相关 Redis 键（队列写入目标 = 基键 + 活跃 serverId）：
                  im:message:private:{serverId} / im:message:system:{serverId}
                  im:max_server_id（当前活跃 serverId 上界）

                建议的处理命令：
                  redis-cli -n 0 PING
                  redis-cli -n 0 INFO memory
                  redis-cli -n 0 GET im:max_server_id
                  # kean 日志：grep "\\[IM 镜像投递\\] 写入队列" <kean 日志文件> | tail -20

                说明：本邮件由 kean 定时巡检自动发出；同一类告警 %d 分钟内只发一次（冷却期内只记日志）。
                """.formatted(
                delta.failed(),
                failureErrorThreshold,
                delta.attempts(),
                delta.pushed(),
                delta.failed(),
                delta.skipped(),
                current.attempts(),
                current.pushed(),
                current.failed(),
                current.skipped(),
                intervalMs,
                imAlertService.cooldownMinutes());
    }

    private String residueBody(List<String> residueKeys, List<String> residueReasons, Long maxServerId) {
        return """
                【课安 IM 告警】疑似残留队列（旧 serverId，无人消费）

                问题类型：存在 im:message:*:{serverId} 队列，其 serverId 已不可能被消费
                当前值：疑似残留 %d 个；im:max_server_id=%s
                判定规则（命中任一即报）：
                  ① serverId > im:max_server_id（计数器被重置，或写入了脏值）；
                  ② 队列长度 > 0 且连续 %d 轮长度完全没变（默认 5 轮 ≈ 5 分钟），说明没有消费者在拉。
                阈值配置项：kean.im.residue-stagnant-rounds

                判定明细：
                %s

                背景：im-server 每次启动都会从 im:max_server_id 自增领取新的 serverId，
                而队列键里带 serverId。于是【重启 / 缩容后旧编号的队列永远不会再被消费】，
                消息会一直躺在 Redis 里直到把内存吃满（docs/ops/im-server-patch.md §6 有记录）。
                本程序只报告、不自动删 —— DEL 会永久丢弃这些消息，必须由人确认后再执行；
                也可以在管理端（POST /api/admin/im/queues/clean）带显式确认串清理。

                建议的处理命令：
                %s
                说明：本邮件由 kean 定时巡检自动发出；同一类告警 %d 分钟内只发一次（冷却期内只记日志）。
                """.formatted(
                residueKeys.size(),
                maxServerId == null ? "（未读到该键）" : String.valueOf(maxServerId),
                stagnantRounds,
                residueReasons,
                ImAlertService.cleanupHint(residueKeys),
                imAlertService.cooldownMinutes());
    }
}
