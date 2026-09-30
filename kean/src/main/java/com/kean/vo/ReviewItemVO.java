package com.kean.vo;

import java.time.LocalDateTime;
import java.util.List;

public record ReviewItemVO(
        Long id,
        Long taskId,
        String courseName,
        String fromNickname,
        String fromAvatarUrl,
        Integer rating,
        List<String> tags,
        String content,
        String targetRole,
        LocalDateTime createdAt
) {
}
