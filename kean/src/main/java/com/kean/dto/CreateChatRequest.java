package com.kean.dto;

import jakarta.validation.constraints.NotNull;

public record CreateChatRequest(
        @NotNull(message = "请选择聊天对象")
        Long peerUserId
) {
}
