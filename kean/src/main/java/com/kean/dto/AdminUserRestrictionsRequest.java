package com.kean.dto;

import jakarta.validation.constraints.Size;

public record AdminUserRestrictionsRequest(
        Integer forbidPublish,
        Integer forbidApply,
        Integer muted,
        Integer days,

        @Size(max = 255, message = "备注最多 255 字")
        String remark
) {
}
