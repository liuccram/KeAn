package com.kean.vo;

import java.time.LocalDateTime;

public record AdminAccountVO(
        Long id,
        String username,
        String nickname,
        String status,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt
) {
}
