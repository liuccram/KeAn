package com.kean.service;

import com.kean.dto.AdminImCleanRequest;
import com.kean.vo.AdminImAlertsVO;
import com.kean.vo.AdminImCleanResultVO;
import com.kean.vo.AdminImMonitorVO;

/**
 * 管理端的 IM 监控与处置（对应 {@code /api/admin/im/**}）。
 *
 * <p>三个方法全部是<b>只读 + 一个受控写操作</b>：</p>
 * <ul>
 *   <li>{@link #stats()} / {@link #alerts(Integer)} —— <b>只读</b>，数据来自巡检的内存快照
 *       （队列长度不是每次请求实时 SCAN，避免管理端刷新给 Redis 加压，也保证与告警邮件、
 *       {@code /health/ready} 看到同一份事实）；</li>
 *   <li>{@link #cleanResidue(AdminImCleanRequest)} —— <b>唯一的写操作</b>：
 *       删除残留队列键，带显式确认串与「活跃 serverId」二次校验，逐键返回结果。</li>
 * </ul>
 */
public interface AdminImService {

    /**
     * IM 监控看板数据（只读）。
     *
     * <p>Redis 不可用时<b>不抛异常</b>：返回 {@code redisAvailable=false} + 说明，
     * 让管理端页面能正常打开。</p>
     */
    AdminImMonitorVO stats();

    /**
     * 告警历史（只读，<b>内存态、重启即丢</b>）。
     *
     * @param limit 最多返回多少条；为 {@code null} / &le;0 时用默认值（50），并统一被缓冲容量截断
     */
    AdminImAlertsVO alerts(Integer limit);

    /**
     * 清理残留队列（<b>写操作</b>）。
     *
     * <p>只允许删除<b>最近一次巡检判定为残留</b>、且通过二次校验的键：
     * ① serverId 不在「活跃 serverId 集合」（在线槽位值 ∪ 最新实例 {@code im:max_server_id}）里；
     * ② 活跃集合本身可读且完整（否则一律拒绝，宁可让运维手工处理）。
     * 删除前会 {@code log.warn} 记录键名与长度，删除后写操作日志。</p>
     *
     * @return 逐键结果（被拒绝是正常结果，不是错误）
     */
    AdminImCleanResultVO cleanResidue(AdminImCleanRequest request);
}
