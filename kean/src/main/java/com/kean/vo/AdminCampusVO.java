package com.kean.vo;

import java.time.LocalDateTime;

public record AdminCampusVO(
        Long id,
        Long schoolId,
        String schoolName,
        String name,
        Integer status,
        LocalDateTime createdAt
) {
}
