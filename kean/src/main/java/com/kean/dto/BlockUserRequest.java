package com.kean.dto;

import jakarta.validation.constraints.NotNull;

public record BlockUserRequest(
        @NotNull(message = "请选择要拉黑的用户")
        Long blockedUserId
) {
}
