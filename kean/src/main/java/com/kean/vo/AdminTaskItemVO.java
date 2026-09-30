package com.kean.vo;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record AdminTaskItemVO(
        Long id,
        String courseName,
        Long publisherId,
        String publisherNickname,
        Long applicantId,
        String applicantNickname,
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate taskDate,
        @JsonFormat(pattern = "HH:mm")
        LocalTime startTime,
        @JsonFormat(pattern = "HH:mm")
        LocalTime endTime,
        LocalDateTime startAt,
        Long schoolId,
        String schoolName,
        Long campusId,
        String campusName,
        String building,
        String classroom,
        String status,
        Integer applyCount,
        BigDecimal reward,
        LocalDateTime createdAt
) {
}
