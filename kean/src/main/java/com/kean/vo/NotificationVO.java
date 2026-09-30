package com.kean.vo;

import java.time.LocalDateTime;

public record NotificationVO(
        Long id,
        String type,
        String title,
        String content,
        String bizType,
        Long bizId,
        Integer readFlag,
        LocalDateTime createdAt
) {
}
