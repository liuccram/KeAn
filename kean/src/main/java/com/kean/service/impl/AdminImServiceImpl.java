package com.kean.service.impl;

import com.kean.dto.AdminImCleanRequest;
import com.kean.im.ImAlertRecord;
import com.kean.im.ImAlertService;
import com.kean.im.ImDeliveryStats;
import com.kean.im.ImQueueInspection;
import com.kean.im.ImQueueMonitorService;
import com.kean.im.ImSenderService;
import com.kean.security.LoginUser;
import com.kean.security.SecurityUtils;
import com.kean.service.AdminImService;
import com.kean.service.OperationLogService;
import com.kean.vo.AdminImAlertsVO;
import com.kean.vo.AdminImCleanResultVO;
import com.kean.vo.AdminImMonitorVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 管理端 IM 监控与处置的实现（{@code /api/admin/im/**}）。
 *
 * <h2>为什么数据来自「最近一次巡检」而不是每次实时查</h2>
 * <p>看板刷新频率远高于 60 秒一轮的巡检。如果每个请求都去 {@code SCAN} + {@code LLEN}，
 * 管理端一刷新就会给 Redis 加压，而且可能出现「看板与告警邮件 / {@code /health/ready}
 * 三份口径不一致」。所以这里统一读 {@link ImQueueMonitorService#lastInspection()}
 * 那份不可变快照 —— <b>状态判定与 {@code /health/ready} 走的是同一个
 * {@link ImQueueMonitorService#healthStatus()}</b>（同源同判据）。</p>
 *
 * <h2>唯一写操作：{@link #cleanResidue}</h2>
 * <p>删 Redis 队列是不可逆的（消息永久丢失），所以这里堆了四道闸：</p>
 * <ol>
 *   <li><b>显式确认串</b>：{@code confirm} 必须等于 {@value com.kean.dto.AdminImCleanRequest#CONFIRM_PHRASE}
 *       （DTO 上还有 {@code @Pattern} 与 {@code @NotBlank}，服务里再兜一层，防止别的调用方绕过控制器）；</li>
 *   <li><b>只删「已被巡检判定为残留」的键</b>：候选来自最近一次巡检的 {@code residue} 标记，
 *       而不是「调用方说哪个就删哪个」；</li>
 *   <li><b>活跃 serverId 二次校验</b>：删之前重新读「在线槽位值 ∪ 最新实例 {@code im:max_server_id}」，
 *       候选 serverId 只要还在这个集合里就<b>拒绝</b>（那说明还有用户挂在上面，或有可能是活着的实例）；
 *       若活跃集合<b>读不完整</b>（槽位键数超过扫描封顶）或 {@code im:max_server_id} 读不到，
 *       则<b>整体拒绝</b> —— 宁可让运维手工处理，也不能误删还有人在用的队列；</li>
 *   <li><b>删前留痕</b>：重新读一次 {@code LLEN}（记录将要丢弃多少条），
 *       {@code log.warn} 记下「管理员 / 键名 / serverId / 长度」，删完写一条操作日志
 *       （{@code OperationLogService}，操作类型 {@code IM_QUEUE_CLEAN}）。</li>
 * </ol>
 *
 * <p><b>刻意不加 {@code @Transactional}</b>：这里唯一的数据库写入是操作日志，
 * 而 {@code OperationLogServiceImpl.record} 自带 try/catch（写日志失败只 warn）。
 * 如果给它套上事务，日志插入失败会让事务被标记 rollback-only，
 * 反而在方法出口抛 {@code UnexpectedRollbackException} 变成 500 ——
 * 那会把「写日志失败」升级成「清理接口报错」，与设计目标相反。
 * Redis 侧本来也不参与数据库事务，所以没有一致性可损失。</p>
 *
 * <h2>Redis 不可用时不返回 500</h2>
 * <p>GET 两个接口读的是内存快照，天然不会因 Redis 抖动而失败（只在返回里标
 * {@code redisAvailable=false}）；POST 里所有 Redis 操作都有 try/catch，
 * 失败会变成逐键结果里的「已拒绝」+ {@code note}，管理员刷新一下就能看到，
 * 而不是面对一个 500。</p>
 */
@Service
public class AdminImServiceImpl implements AdminImService {

    private static final Logger log = LoggerFactory.getLogger(AdminImServiceImpl.class);

    /** 历史缓冲为空/未配置 limit 时管理端默认取多少条；实际还会被缓冲容量截断。 */
    private static final int DEFAULT_ALERT_LIMIT = 50;

    private final StringRedisTemplate redis;
    private final ImSenderService imSenderService;
    private final ImQueueMonitorService imQueueMonitorService;
    private final ImAlertService imAlertService;
    private final OperationLogService operationLogService;

    public AdminImServiceImpl(
            StringRedisTemplate redis,
            ImSenderService imSenderService,
            ImQueueMonitorService imQueueMonitorService,
            ImAlertService imAlertService,
            OperationLogService operationLogService
    ) {
        this.redis = redis;
        this.imSenderService = imSenderService;
        this.imQueueMonitorService = imQueueMonitorService;
        this.imAlertService = imAlertService;
        this.operationLogService = operationLogService;
    }

    @Override
    public AdminImMonitorVO stats() {
        ImDeliveryStats counters = imSenderService.snapshot();
        // 与 /health/ready 的 im 字段同源：同一个方法、同一份巡检结论。
        String status = imQueueMonitorService.healthStatus();
        boolean enabled = imSenderService.enabled();
        ImQueueInspection inspection = imQueueMonitorService.lastInspection();
        LocalDateTime now = LocalDateTime.now();

        AdminImMonitorVO.Counters counterVo =
                new AdminImMonitorVO.Counters(counters.attempts(), counters.pushed(),
                        counters.failed(), counters.skipped());

        if (inspection == null) {
            // 启用但第一轮巡检还没跑（或组件刚启动）：如实说「还没有数据」，不编造队列状态。
            return new AdminImMonitorVO(status, enabled, false,
                    "尚无巡检结果：IM 已启用但第一轮巡检还没执行（默认 60 秒一轮，稍后刷新）",
                    counterVo, List.of(), 0L, 0, false, List.of(), null, 0L, null, now);
        }

        List<AdminImMonitorVO.QueueItem> queues = new ArrayList<>(inspection.queueCount());
        for (ImQueueInspection.QueueStat stat : inspection.queues()) {
            queues.add(new AdminImMonitorVO.QueueItem(
                    stat.key(), stat.length(), stat.serverId(), stat.residue(), stat.residueReason()));
        }
        List<String> residueKeys = inspection.residueKeys();
        return new AdminImMonitorVO(
                status,
                enabled,
                inspection.redisAvailable(),
                inspection.message(),
                counterVo,
                List.copyOf(queues),
                inspection.queueTotal(),
                inspection.queueCount(),
                !residueKeys.isEmpty(),
                residueKeys,
                inspection.maxServerId(),
                inspection.failedDelta(),
                inspection.inspectedAt(),
                now);
    }

    @Override
    public AdminImAlertsVO alerts(Integer limit) {
        int capacity = imAlertService.historySize();
        int effective = limit == null || limit <= 0
                ? Math.min(DEFAULT_ALERT_LIMIT, capacity)
                : Math.min(limit, capacity);
        List<ImAlertRecord> records = imAlertService.history(effective);
        List<AdminImAlertsVO.Item> items = new ArrayList<>(records.size());
        for (ImAlertRecord record : records) {
            items.add(new AdminImAlertsVO.Item(
                    record.time(), record.kind(), record.level(), record.summary(),
                    record.currentValue(), record.threshold(), record.keys(),
                    record.mailSent(), record.mailNote()));
        }
        String note = "告警历史保存在 kean 进程内存中（最多 " + capacity + " 条），"
                + "进程重启或重新部署即清空；需要长期留存请查运维邮箱，或 kean 日志里的 [IM 告警] 关键字。";
        return new AdminImAlertsVO(List.copyOf(items), items.size(), effective, capacity, note);
    }

    @Override
    public AdminImCleanResultVO cleanResidue(AdminImCleanRequest request) {
        LocalDateTime now = LocalDateTime.now();

        // 闸门 ①：显式确认串（DTO 已校验，这里再兜一层，防其它调用方绕过控制器）。
        if (request == null || !AdminImCleanRequest.CONFIRM_PHRASE.equals(request.confirm())) {
            return new AdminImCleanResultVO(0, 0, 0, List.of(), now,
                    "已拒绝：确认串不正确（必须为 " + AdminImCleanRequest.CONFIRM_PHRASE + "），未执行任何删除");
        }

        // 闸门 ②：候选只能来自「最近一次巡检判定为残留」的键。
        ImQueueInspection inspection = imQueueMonitorService.lastInspection();
        if (inspection == null) {
            return new AdminImCleanResultVO(0, 0, 0, List.of(), now,
                    "没有可清理的判定依据：尚无巡检结果（IM 可能未启用，或第一轮巡检还没跑），未执行任何删除");
        }
        if (!inspection.redisAvailable()) {
            return new AdminImCleanResultVO(0, 0, 0, List.of(), now,
                    "没有可清理的判定依据：最近一次巡检未读到 Redis（数据不可用），未执行任何删除");
        }
        List<ImQueueInspection.QueueStat> candidates = new ArrayList<>();
        for (ImQueueInspection.QueueStat stat : inspection.queues()) {
            if (!stat.residue()) {
                continue;
            }
            if (request.serverIds() != null && !request.serverIds().isEmpty()
                    && !request.serverIds().contains(stat.serverId())) {
                continue;
            }
            candidates.add(stat);
        }
        if (candidates.isEmpty()) {
            return new AdminImCleanResultVO(0, 0, 0, List.of(), now,
                    "没有候选：最近一次巡检没有判定出残留队列（或指定的 serverIds 中不含残留队列），未执行任何删除");
        }

        // 闸门 ③：重新读取「活跃 serverId 集合」，读不完整就整体拒绝。
        ImQueueMonitorService.ActiveServerIds active;
        try {
            active = imQueueMonitorService.liveServerIds();
        } catch (Exception ex) {
            log.warn("[IM 残留清理] 活跃 serverId 集合读取失败，已拒绝本次清理：{}", ex.getMessage());
            List<AdminImCleanResultVO.Item> refused = new ArrayList<>(candidates.size());
            for (ImQueueInspection.QueueStat stat : candidates) {
                refused.add(new AdminImCleanResultVO.Item(stat.key(), stat.serverId(), stat.length(), false,
                        "已拒绝：活跃 serverId 集合读取失败（Redis 不可用），无法确认安全性"));
            }
            return new AdminImCleanResultVO(candidates.size(), 0, refused.size(), List.copyOf(refused), now,
                    "未执行任何删除：Redis 不可用，无法确认活跃 serverId 集合");
        }

        LoginUser admin = SecurityUtils.currentUserOrNull();
        Long adminId = admin == null ? null : admin.userId();

        List<AdminImCleanResultVO.Item> results = new ArrayList<>(candidates.size());
        int deleted = 0;
        int skipped = 0;
        for (ImQueueInspection.QueueStat candidate : candidates) {
            String refusal = refusalReason(candidate, active);
            if (refusal != null) {
                skipped++;
                results.add(new AdminImCleanResultVO.Item(
                        candidate.key(), candidate.serverId(), candidate.length(), false, refusal));
                continue;
            }
            long length;
            try {
                Long size = redis.opsForList().size(candidate.key());
                length = size == null ? 0L : size;
            } catch (Exception ex) {
                skipped++;
                // 对外只写异常类型：Redis 客户端的 message 里可能带 host:port，属于基础设施信息，不外泄。
                results.add(new AdminImCleanResultVO.Item(candidate.key(), candidate.serverId(),
                        candidate.length(), false, "已拒绝：重新读取长度失败（" + ex.getClass().getSimpleName() + "）"));
                continue;
            }

            // 闸门 ④：删前留痕（丢了什么、谁删的、什么时候）。
            log.warn("[IM 残留清理] 管理员 userId={} 即将删除残留队列 key={} serverId={}，"
                            + "该队列当前长度={}（其中的消息将被永久丢弃）",
                    adminId, candidate.key(), candidate.serverId(), length);

            boolean ok;
            try {
                ok = Boolean.TRUE.equals(redis.delete(candidate.key()));
            } catch (Exception ex) {
                skipped++;
                results.add(new AdminImCleanResultVO.Item(candidate.key(), candidate.serverId(), length,
                        false, "删除失败（Redis 不可用，" + ex.getClass().getSimpleName() + "）"));
                operationLogService.record("IM_QUEUE_CLEAN", "IM_QUEUE", candidate.key(),
                        "清理 IM 残留队列失败 key=" + candidate.key() + " serverId=" + candidate.serverId()
                                + " 长度=" + length + "（" + ex.getClass().getSimpleName() + "）");
                continue;
            }
            if (ok) {
                deleted++;
                results.add(new AdminImCleanResultVO.Item(candidate.key(), candidate.serverId(), length,
                        true, "已删除（丢弃未推送消息 " + length + " 条）"));
            } else {
                skipped++;
                results.add(new AdminImCleanResultVO.Item(candidate.key(), candidate.serverId(), length,
                        false, "未删除：键已不存在（可能已被消费或人工删除）"));
            }
            operationLogService.record("IM_QUEUE_CLEAN", "IM_QUEUE", candidate.key(),
                    "清理 IM 残留队列 key=" + candidate.key() + " serverId=" + candidate.serverId()
                            + " 长度=" + length + (ok ? "（成功）" : "（未删除：键已不存在）"));
        }

        String note;
        if (deleted == 0 && skipped == candidates.size() && !active.usableForCleanup()) {
            note = "未执行任何删除：活跃 serverId 集合不可用或不完整"
                    + "（在线槽位键数超过 kean.im.scan-max-keys 封顶，或 im:max_server_id 读不到），"
                    + "无法确认候选 serverId 已无人使用，请人工确认后清理";
        } else if (deleted == 0) {
            note = "未执行任何删除：" + skipped + " 个候选均未通过安全校验（原因见逐键结果）";
        } else if (skipped > 0) {
            note = "部分完成：删除 " + deleted + " 个，未删除 " + skipped + " 个（原因见逐键结果）";
        } else {
            note = "已完成：删除 " + deleted + " 个残留队列";
        }
        log.warn("[IM 残留清理] 管理员 userId={} 清理完成：候选={} 删除={} 未删除={}",
                adminId, candidates.size(), deleted, skipped);
        return new AdminImCleanResultVO(candidates.size(), deleted, skipped, List.copyOf(results), now, note);
    }

    /**
     * 闸门 ③ 的逐键判定：返回 {@code null} 表示「允许删除」，否则返回拒绝原因。
     *
     * <p>判定顺序刻意从「最不可信」到「最可信」：先看活跃集合本身能不能用，
     * 再看 serverId 是否还在活跃集合里。</p>
     */
    private static String refusalReason(ImQueueInspection.QueueStat candidate,
                                        ImQueueMonitorService.ActiveServerIds active) {
        if (!active.usableForCleanup()) {
            return "已拒绝：活跃 serverId 集合不可用或不完整"
                    + "（在线槽位键数超过扫描封顶，或 im:max_server_id 读不到），无法确认该 serverId 已无人使用";
        }
        if (candidate.serverId() == null) {
            return "已拒绝：无法从键名解析出 serverId，不能确认它已无人使用";
        }
        if (active.ids().contains(candidate.serverId())) {
            return "已拒绝：serverId=" + candidate.serverId()
                    + " 仍在活跃集合中（有用户挂在该实例上，或它就是最新实例），删除会丢失在线消息";
        }
        if (Objects.equals(candidate.serverId(), active.maxServerId())) {
            // 理论上上面那条已经覆盖（liveServerIds 一定把 max 放进集合），这里是双保险。
            return "已拒绝：serverId=" + candidate.serverId() + " 是最新实例编号（im:max_server_id），"
                    + "删除它可能丢失在线消息";
        }
        return null;
    }
}
