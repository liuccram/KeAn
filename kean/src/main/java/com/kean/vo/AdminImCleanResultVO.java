package com.kean.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端清理 IM 残留队列的结果：{@code POST /api/admin/im/queues/clean} 的返回体
 * （<b>本组接口里唯一的写操作</b>）。
 *
 * <p>逐键返回，便于前端把「哪些删了、哪些被拒绝、为什么」如实展示 ——
 * 拒绝是<b>正常结果</b>（说明该键没有通过安全校验），不是错误。</p>
 *
 * @param requested 本次请求考虑的候选键数（来自最近一次巡检判定为残留的队列，并已按请求里的 serverIds 过滤）
 * @param deleted   实际删除成功的键数
 * @param skipped   被拒绝 / 未删除的键数
 * @param results   逐键结果（新的在前不重要，这里保持巡检顺序）
 * @param cleanedAt 本次操作时间
 * @param note      一句话说明（Redis 不可用 / 数据不可用 / 只有部分键通过校验 等），可直接展示
 */
public record AdminImCleanResultVO(
        int requested,
        int deleted,
        int skipped,
        List<Item> results,
        LocalDateTime cleanedAt,
        String note
) {

    /**
     * 单个键的处理结果。
     *
     * @param key      队列键名
     * @param serverId 键尾的 serverId（解析不出为 {@code null}）
     * @param length   删除前重新读取的队列长度（删之前记录，便于追溯丢了多少条）
     * @param deleted  是否删除成功
     * @param reason   结果原因（成功时是「已删除」，失败时说明为什么拒绝）
     */
    public record Item(String key, Long serverId, long length, boolean deleted, String reason) {
    }
}
