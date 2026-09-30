package com.kean.vo;

import java.time.LocalDateTime;

public record ReportAppealVO(
        Long id,
        Long reportId,
        Long userId,
        String nickname,
        String content,
        java.util.List<String> images,
        String status,
        String handleRemark,
        Long handlerId,
        String handlerNickname,
        LocalDateTime createdAt,
        LocalDateTime handledAt
) {
}
