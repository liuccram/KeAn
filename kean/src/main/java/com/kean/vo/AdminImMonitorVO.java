package com.kean.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端 IM 监控看板：{@code GET /api/admin/im/stats} 的返回体（只读）。
 *
 * <p>数据来源与 {@code /health/ready} 的 {@code im} 字段<b>完全同源</b>：
 * 状态取自 {@code ImQueueMonitorService#healthStatus()}，
 * 队列/残留/上界/失败增量取自同一个 {@code ImQueueInspection} 对象
 * （最近一次巡检的不可变结果，默认 60 秒一轮）。</p>
 *
 * <p><b>数据是「最近一次巡检」的快照，不是实时查询</b> —— 这样管理端刷新不会向 Redis 发 SCAN，
 * 也保证看板与告警邮件、探针看到的是同一份事实。前端应显示 {@link #lastInspectionAt} 让用户
 * 知道数据有多新。</p>
 *
 * @param status           IM 总体状态：{@code DISABLED} / {@code UP} / {@code DEGRADED}（与 {@code /health/ready} 同源）
 * @param enabled          镜像投递是否启用（{@code IM_JWT_SECRET} 配好且未被 {@code KEAN_IM_MIRROR_ENABLED=false} 关掉）
 * @param redisAvailable   本轮巡检是否真的读到了 Redis；{@code false} 时下列队列数据不可信，前端应显示 {@link #message}
 * @param message          一句话说明（数据不可用的原因 / 本轮结论），<b>不含任何凭据</b>
 * @param counters         投递计数累计快照（{@code ImDeliveryStats}）
 * @param queues           各队列（{@code key} / 长度 / serverId / 是否残留 / 残留原因）
 * @param queueTotal       队列长度合计（Redis 里积压了多少条没推出去的消息）
 * @param queueCount       队列个数
 * @param residueFound     是否发现残留队列（列表见 {@link #residueKeys}）
 * @param residueKeys      残留队列键名（清理接口的候选，仍会二次校验）
 * @param maxServerId      {@code im:max_server_id} 当前值（读不到为 {@code null}）
 * @param failedInLastCycle 最近一个巡检周期内的投递失败增量（告警阈值比较的就是它）
 * @param lastInspectionAt 最近一次真正执行过的巡检时间；IM 未启用或尚未巡检为 {@code null}
 * @param queriedAt        本次查询时间
 */
public record AdminImMonitorVO(
        String status,
        boolean enabled,
        boolean redisAvailable,
        String message,
        Counters counters,
        List<QueueItem> queues,
        long queueTotal,
        int queueCount,
        boolean residueFound,
        List<String> residueKeys,
        Long maxServerId,
        long failedInLastCycle,
        LocalDateTime lastInspectionAt,
        LocalDateTime queriedAt
) {

    /** 投递计数（累计值，进程重启归零；含义与粒度见 {@code ImDeliveryStats}）。 */
    public record Counters(long attempts, long pushed, long failed, long skipped) {
    }

    /**
     * 单个队列。
     *
     * @param key           完整键名（{@code im:message:private:{serverId}} / {@code im:message:system:{serverId}}）
     * @param length        {@code LLEN}
     * @param serverId      键尾的 serverId（解析不出为 {@code null}）
     * @param residue       是否被判定为残留（旧 serverId，无人消费）
     * @param residueReason 残留原因；未残留为 {@code null}
     */
    public record QueueItem(String key, long length, Long serverId, boolean residue, String residueReason) {
    }
}
