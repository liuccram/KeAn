package com.kean.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端 IM 告警历史：{@code GET /api/admin/im/alerts} 的返回体（只读）。
 *
 * <p>⚠️ <b>内存态：进程重启 / 重新部署即清空。</b> 历史保存在 kean 进程内的环形缓冲里
 * （默认最近 {@code kean.im.alert-history-size=50} 条），<b>不落库、不加表、不加迁移</b> ——
 * 这是本轮刻意接受的取舍。需要长期留存的告警请查运维邮箱，或 kean 日志里的
 * {@code [IM 告警]} 关键字。</p>
 *
 * @param list     告警记录，<b>新的在前</b>
 * @param size     本次返回条数
 * @param limit    本次请求的条数上限（已被 {@link #capacity} 截断）
 * @param capacity 历史缓冲容量（最多保留多少条）
 * @param note     给前端的说明（内存态 / 重启丢失 / 邮件与日志这两个替代来源），可直接展示
 */
public record AdminImAlertsVO(
        List<Item> list,
        int size,
        int limit,
        int capacity,
        String note
) {

    /**
     * 一条告警记录。
     *
     * @param time         发现时间
     * @param kind         类别：{@code queue-backlog}（队列堆积）/ {@code delivery-failure}（投递失败）/ {@code queue-residue}（残留队列）
     * @param level        级别：{@code WARN} / {@code ERROR}
     * @param summary      问题类型（一句人话）
     * @param currentValue 当前值
     * @param threshold    阈值与配置项名
     * @param keys         相关 Redis 键名（不含凭据）
     * @param mailSent     是否真的发出了告警邮件
     * @param mailNote     未发邮件的原因（冷却中 / 告警关闭 / 无收件人 / 邮件未就绪 / 发送失败）；发出时为 {@code null}
     */
    public record Item(
            LocalDateTime time,
            String kind,
            String level,
            String summary,
            String currentValue,
            String threshold,
            List<String> keys,
            boolean mailSent,
            String mailNote
    ) {
    }
}
