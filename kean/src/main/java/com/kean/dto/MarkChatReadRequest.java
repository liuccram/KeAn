package com.kean.dto;

/**
 * 标记会话已读请求体（可选）。
 *
 * <p>{@code maxSeq} = 客户端当前已经读到的最大 seq_no。服务端据此写已读位点、
 * 把对方发给我的、seq_no &lt;= maxSeq 的消息置为已读，并给对方推 READ 事件。
 * 老客户端不传这个体（或者传空对象）时保持原行为。
 */
public record MarkChatReadRequest(Long maxSeq) {
}
