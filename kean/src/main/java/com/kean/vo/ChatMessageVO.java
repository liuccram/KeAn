package com.kean.vo;

import java.time.LocalDateTime;

public record ChatMessageVO(
        Long id,
        Long sessionId,
        Long senderId,
        String msgType,
        String content,
        String url,
        LocalDateTime createdAt,
        Boolean mine
) {
}
