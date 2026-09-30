package com.kean.vo;

import java.time.LocalDateTime;

public record ChatSessionVO(
        Long id,
        Long peerUserId,
        String peerNickname,
        String peerAvatarUrl,
        String lastContent,
        LocalDateTime lastMessageAt,
        Integer unreadCount,
        boolean peerMuted,
        boolean peerBanned
) {
}
