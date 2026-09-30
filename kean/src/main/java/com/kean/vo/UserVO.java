package com.kean.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record UserVO(
        Long id,
        String role,
        String username,
        String phone,
        String email,
        String nickname,
        String gender,
        String avatarUrl,
        String coverUrl,
        Long schoolId,
        Long campusId,
        String schoolName,
        String campusName,
        Integer schoolChangeCount,
        Integer completedCount,
        BigDecimal ratingAvg,
        Integer ratingCount,
        Integer publishCompletedCount,
        BigDecimal publishRatingAvg,
        Integer publishRatingCount,
        BigDecimal applyRatingAvg,
        Integer applyRatingCount,
        Integer cancelledCount,
        Integer reportedCount,
        String status,
        Integer forbidPublish,
        Integer forbidApply,
        Integer muted,
        LocalDateTime forbidPublishUntil,
        LocalDateTime forbidApplyUntil,
        LocalDateTime mutedUntil,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt,
        Integer privateAccount,
        Boolean online,
        Boolean mustChangePassword
) {
}
