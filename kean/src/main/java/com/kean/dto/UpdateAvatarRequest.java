package com.kean.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateAvatarRequest(
        @NotBlank(message = "请先上传头像")
        String objectKey
) {
}
