package com.kean.dto;

public record TaskQuery(
        String keyword,
        String taskDate,
        String timeSlot,
        Long courseId,
        Long campusId,
        String status,
        Long schoolId,
        Long page,
        Long size
) {
}
