package com.kean.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendChatMessageRequest(
        String msgType,

        @NotBlank(message = "消息不能为空")
        @Size(max = 2000, message = "消息最多 2000 字")
        String content
) {
}
