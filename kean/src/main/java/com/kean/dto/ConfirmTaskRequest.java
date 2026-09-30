package com.kean.dto;

import jakarta.validation.constraints.NotBlank;

public record ConfirmTaskRequest(
        @NotBlank(message = "请上传履约现场照片")
        String objectKey
) {
}
