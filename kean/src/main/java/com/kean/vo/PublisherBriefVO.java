package com.kean.vo;

import java.math.BigDecimal;

public record PublisherBriefVO(
        Long id,
        String nickname,
        String avatarUrl,
        String gender,
        String schoolName,
        String campusName,
        Integer completedCount,
        BigDecimal ratingAvg,
        Integer ratingCount,
        Integer cancelledCount,
        Integer reportedCount,
        String status,
        Integer forbidPublish,
        Integer forbidApply,
        Integer muted
) {
}
