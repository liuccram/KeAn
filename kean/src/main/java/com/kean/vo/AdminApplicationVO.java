package com.kean.vo;

import java.time.LocalDateTime;

public record AdminApplicationVO(
        Long id,
        Long taskId,
        Long applicantId,
        String nickname,
        String schoolName,
        String message,
        String status,
        LocalDateTime createdAt
) {
}
