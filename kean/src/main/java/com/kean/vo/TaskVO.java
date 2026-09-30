package com.kean.vo;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record TaskVO(
        Long id,
        Long publisherId,
        Long courseId,
        String courseName,
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate taskDate,
        @JsonFormat(pattern = "HH:mm")
        LocalTime startTime,
        @JsonFormat(pattern = "HH:mm")
        LocalTime endTime,
        LocalDateTime startAt,
        LocalDateTime endAt,
        Long schoolId,
        Long campusId,
        String campusName,
        String building,
        String classroom,
        Integer computerLab,
        Integer requirePhoto,
        String genderRequirement,
        BigDecimal reward,
        String reason,
        String requirement,
        String remark,
        String status,
        Integer applyCount,
        LocalDateTime createdAt,
        PublisherBriefVO publisher,
        PublisherBriefVO applicant,
        Boolean mine,
        Integer publisherConfirmed,
        Integer applicantConfirmed,
        Integer publisherCompleted,
        Integer applicantCompleted,
        Long acceptedApplicationId,
        String myApplicationStatus,
        Long myApplicationId,
        Boolean matchedApplicant,
        String matchedApplicantNickname,
        Long matchedApplicantId,
        Boolean canReview,
        Integer myReviewRating,
        Boolean favorited,
        String fulfillPhotoUrl
) {
}
