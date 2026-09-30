package com.kean.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ApplicationVO(
        Long id,
        Long taskId,
        Long applicantId,
        String nickname,
        String message,
        String status,
        LocalDateTime createdAt,
        Integer completedCount,
        BigDecimal ratingAvg,
        Integer ratingCount,
        Integer cancelledCount,
        Integer reportedCount,
        String userStatus,
        Integer forbidPublish,
        Integer forbidApply,
        Integer muted
) {
}
