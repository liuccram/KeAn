package com.kean.vo;

import java.time.LocalDateTime;
import java.util.List;

public record ReportVO(
        Long id,
        Long reporterId,
        String reporterNickname,
        String targetType,
        Long targetId,
        String targetLabel,
        Long targetUserId,
        String targetUserNickname,
        String targetUserStatus,
        Integer forbidPublish,
        Integer forbidApply,
        Integer muted,
        String type,
        String typeLabel,
        String description,
        List<String> images,
        String status,
        String handleResult,
        String handleRemark,
        Long handlerId,
        String handlerNickname,
        LocalDateTime createdAt,
        LocalDateTime handledAt,
        List<ReportAppealVO> appeals
) {
}
