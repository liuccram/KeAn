package com.kean.vo;

import java.time.LocalDateTime;

public record AdminSchoolVO(
        Long id,
        String name,
        Long provinceId,
        String provinceName,
        Integer status,
        LocalDateTime createdAt
) {
}
