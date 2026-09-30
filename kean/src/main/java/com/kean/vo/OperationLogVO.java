package com.kean.vo;

import java.time.LocalDateTime;

public record OperationLogVO(
        Long id,
        Long adminId,
        String adminName,
        String operationType,
        String targetType,
        String targetId,
        String result,
        String ip,
        String description,
        LocalDateTime createdAt
) {
}
