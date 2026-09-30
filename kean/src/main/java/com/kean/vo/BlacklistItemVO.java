package com.kean.vo;

import java.time.LocalDateTime;

public record BlacklistItemVO(
        Long id,
        Long blockedUserId,
        String nickname,
        String avatarUrl,
        String campusName,
        LocalDateTime createdAt
) {
}
