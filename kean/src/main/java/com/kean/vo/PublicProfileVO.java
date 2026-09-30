package com.kean.vo;

import java.math.BigDecimal;
import java.util.List;

public record PublicProfileVO(
        Long id,
        String nickname,
        String avatarUrl,
        String gender,
        String schoolName,
        String campusName,
        Integer completedCount,
        BigDecimal ratingAvg,
        Integer ratingCount,
        Integer publishCompletedCount,
        BigDecimal publishRatingAvg,
        Integer publishRatingCount,
        BigDecimal applyRatingAvg,
        Integer applyRatingCount,
        Integer privateAccount,
        boolean limited,
        boolean mine,
        List<ReviewItemVO> reviews
) {
}
