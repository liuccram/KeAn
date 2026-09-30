package com.kean.vo;

import java.time.LocalDateTime;

public record AnnouncementVO(
        Long id,
        String title,
        String content,
        String status,
        String scope,
        Long schoolId,
        String schoolName,
        Long publisherId,
        String publisherName,
        LocalDateTime publishedAt,
        LocalDateTime createdAt,
        Long targetUserId
) {
}
