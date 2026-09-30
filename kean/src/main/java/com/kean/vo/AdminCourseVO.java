package com.kean.vo;

import java.time.LocalDateTime;

public record AdminCourseVO(
        Long id,
        Long schoolId,
        String schoolName,
        String courseCode,
        String courseName,
        Integer status,
        LocalDateTime createdAt
) {
}
