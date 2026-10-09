package com.kean.vo;

import java.time.LocalDateTime;

/**
 * 私聊消息出参。
 *
 * <p>字段只做追加，不调整既有字段顺序/名称，保证老客户端（uni-kean 现存版本读的是
 * id/sessionId/senderId/msgType/content/url/createdAt/mine）解析不受影响。
 *
 * <p>注意消息类型字段：<b>请求</b>同时接受 {@code type} 与 {@code msgType}（见
 * {@link com.kean.dto.SendChatMessageRequest}），但<b>响应</b>沿用既有的 {@code msgType}，
 * 不额外输出 {@code type}，避免老客户端读到的字段语义发生分歧。
 *
 * <p>本轮新增（对照 box-im 消息模型）：
 * <ul>
 *     <li>{@code seqNo}：会话内连续递增序号，客户端增量拉取用（GET .../messages?afterSeq=）。</li>
 *     <li>{@code localId}：客户端生成的幂等 id，回显给发送方做本地消息对账。</li>
 *     <li>{@code status}：0 未读 / 1 已发送 / 2 撤回 / 3 已读（box 语义）。</li>
 *     <li>{@code readAt}：被置为已读的时间；未读为 null。</li>
 * </ul>
 */
public record ChatMessageVO(
        Long id,
        Long sessionId,
        Long senderId,
        String msgType,
        String content,
        String url,
        LocalDateTime createdAt,
        Boolean mine,
        Long seqNo,
        String localId,
        Integer status,
        LocalDateTime readAt
) {
}
