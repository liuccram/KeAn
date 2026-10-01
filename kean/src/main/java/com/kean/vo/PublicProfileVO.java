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
        /** 1 = 隐私账号。此时 limited=true、统计字段为 null，但 avatarUrl 与 nickname 仍返回。 */
        Integer privateAccount,
        boolean limited,
        boolean mine,
        List<ReviewItemVO> reviews
) {
}
