package com.kean.vo;

import java.time.LocalDateTime;

/**
 * 私聊会话出参。
 *
 * <p>{@code lastSeqNo} 是本轮新增字段（追加在末尾，老客户端不受影响）：
 * 会话内当前最大 seq_no，客户端可以用它作为增量拉取游标、也能判断自己会话列表
 * 是否需要拉新消息。未读口径仍由 {@code unreadCount} 表达，本轮未改动。
 */
public record ChatSessionVO(
        Long id,
        Long peerUserId,
        String peerNickname,
        String peerAvatarUrl,
        String lastContent,
        LocalDateTime lastMessageAt,
        Integer unreadCount,
        boolean peerMuted,
        boolean peerBanned,
        Long lastSeqNo
) {
}
