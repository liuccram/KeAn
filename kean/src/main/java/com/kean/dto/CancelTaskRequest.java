package com.kean.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelTaskRequest(
        @NotBlank(message = "请填写取消原因")
        @Size(min = 2, max = 255, message = "取消原因须为 2-255 字")
        String reason
) {
}
