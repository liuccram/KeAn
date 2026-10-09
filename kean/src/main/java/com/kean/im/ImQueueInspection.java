package com.kean.im;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一次 IM 队列巡检的<b>不可变结果</b>（第 ② 项的产出 + 管理端看板的数据源）。
 *
 * <h2>为什么单独抽一个 record（「同源」的落点）</h2>
 * <p>本轮有两个消费方需要「IM 状态」：</p>
 * <ul>
 *   <li>{@code GET /health/ready} 的 {@code im} 字段（运维探针）；</li>
 *   <li>{@code GET /api/admin/im/stats}（管理端看板）。</li>
 * </ul>
 * <p>两者<b>必须同源同判据</b>，否则会出现「探针说 UP、看板说 DEGRADED」这种互相打脸的场面。
 * 做法：巡检任务每轮只产出<b>一个</b> {@code ImQueueInspection} 对象并整体替换
 * （{@code volatile} publish），状态判定（{@link #status}）在<b>产出时</b>算好写进对象，
 * 两个消费方都只是读同一个对象，谁都不再各自重算一遍。</p>
 *
 * <h2>只读、无副作用</h2>
 * <p>本对象只是数据；删队列等写操作在管理端的 <b>POST</b> 接口里显式进行（见
 * {@code AdminImService#cleanResidue}），并且会重新校验「活跃 serverId」后才动手。</p>
 *
 * @param status        总体状态：{@code DISABLED} / {@code UP} / {@code DEGRADED}（与 {@code /health/ready} 的 {@code im} 同一取值域）
 * @param redisAvailable 本轮<b>是否真的成功读过 Redis</b>。{@code false} 时下面的队列/残留数据一律不可信
 *                       （IM 未启用时也是 {@code false}：那一轮根本没读 Redis）
 * @param message       人类可读的说明（数据不可用的原因 / 一句话结论），<b>不含任何凭据</b>
 * @param queues        本轮扫到的全部队列（含长度与残留标记）
 * @param warnQueues    长度超过 WARN 阈值（未超 ERROR）的队列
 * @param errorQueues   长度超过 ERROR 阈值的队列
 * @param maxServerId   {@code im:max_server_id} 的当前值；读不到为 {@code null}
 * @param failedDelta   最近一个巡检周期内的投递失败增量（来自 {@link ImDeliveryStats#since}）
 * @param inspectedAt   本轮巡检时间（IM 未启用 / 尚未巡检时为 {@code null}）
 */
public record ImQueueInspection(
        String status,
        boolean redisAvailable,
        String message,
        List<QueueStat> queues,
        List<QueueStat> warnQueues,
        List<QueueStat> errorQueues,
        Long maxServerId,
        long failedDelta,
        LocalDateTime inspectedAt
) {

    /**
     * 单个队列的状态。
     *
     * @param key           完整队列键（{@code im:message:private:{serverId}} / {@code im:message:system:{serverId}}）
     * @param serverId      从键尾解析出的 serverId；后缀不是数字时为 {@code null}
     * @param length        {@code LLEN} 结果
     * @param residue       是否被判定为「残留队列」（旧 serverId，无人消费）
     * @param residueReason 判定为残留的原因（未残留时为 {@code null}）
     */
    public record QueueStat(String key, Long serverId, long length, boolean residue, String residueReason) {
    }

    /** 队列总长度（所有队列长度之和）—— 直接反映「Redis 里压着多少条没推出去的消息」。 */
    public long queueTotal() {
        long total = 0L;
        for (QueueStat stat : queues) {
            total += stat.length();
        }
        return total;
    }

    public int queueCount() {
        return queues.size();
    }

    public List<String> residueKeys() {
        return queues.stream().filter(QueueStat::residue).map(QueueStat::key).toList();
    }

    /** IM 未启用时的占位结果（那一轮根本没读 Redis）。 */
    public static ImQueueInspection disabled(String message) {
        return new ImQueueInspection(ImQueueMonitorService.STATUS_DISABLED, false, message,
                List.of(), List.of(), List.of(), null, 0L, null);
    }
}
