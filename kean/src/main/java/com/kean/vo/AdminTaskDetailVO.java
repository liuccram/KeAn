package com.kean.vo;

import java.util.List;

public record AdminTaskDetailVO(
        AdminTaskItemVO task,
        String reason,
        String requirement,
        String remark,
        String genderRequirement,
        Integer computerLab,
        Integer requirePhoto,
        Integer publisherConfirmed,
        Integer applicantConfirmed,
        Integer publisherCompleted,
        Integer applicantCompleted,
        String fulfillPhotoUrl,
        String cancelReason,
        String cancelledBy,
        Long acceptedApplicationId,
        List<AdminTimelineVO> timeline
) {
}
