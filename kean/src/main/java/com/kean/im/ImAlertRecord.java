package com.kean.im;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一条 IM 监控告警记录（第 ③ 项的<b>结构化</b>形态，同时也是管理端
 * {@code GET /api/admin/im/alerts} 的数据源）。
 *
 * <h2>为什么要有结构化形态</h2>
 * <p>邮件正文是给人看的自由文本，管理端看板需要的是字段：时间 / 类型 / 级别 /
 * 当前值 / 阈值 / 键名 / 是否已发邮件。两者刻意分开：
 * 邮件正文（可操作、带命令）照旧由 {@link ImQueueMonitorService} 组装，
 * 这里只保留「一眼能看明白」的字段，不把命令塞进接口返回。</p>
 *
 * <h2>内存态，重启即丢</h2>
 * <p>这些记录保存在 {@link ImAlertService} 的<b>进程内</b>环形缓冲里（默认最近
 * {@code kean.im.alert-history-size=50} 条），<b>不落库、不加表、不加迁移</b>。
 * 进程重启 / 重新部署后历史清空 —— 这是本轮刻意接受的取舍：
 * 需要长期留存的告警请查运维邮箱或 kean 日志（关键字 {@code [IM 告警]}）。</p>
 *
 * @param time         发现时间（应用时区，与项目其余时间字段一致，Jackson 序列化成 ISO-8601）
 * @param kind         告警类别（冷却粒度）：{@code queue-backlog} / {@code delivery-failure} / {@code queue-residue}
 * @param level        级别：{@code WARN} / {@code ERROR}
 * @param summary      问题类型（一句人话）
 * @param currentValue 当前值（例如「队列 im:message:private:3 长度=6200」）
 * @param threshold    阈值与配置项名（例如「ERROR &gt; 5000（kean.im.queue-error-threshold）」）
 * @param keys         相关 Redis 键名（不含任何凭据）
 * @param mailSent     <b>是否真的发出了告警邮件</b>（false 可能是冷却中 / 告警关闭 / 无收件人 / 邮件未就绪 / 发送失败）
 * @param mailNote     未发邮件时的一句话原因；发出时为 {@code null}
 */
public record ImAlertRecord(
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
