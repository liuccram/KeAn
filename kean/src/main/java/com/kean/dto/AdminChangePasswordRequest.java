package com.kean.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminChangePasswordRequest(
        @NotBlank(message = "请填写原密码")
        String oldPassword,

        @NotBlank(message = "请填写新密码")
        @Size(min = 8, max = 32, message = "密码长度为 8-32 位")
        String newPassword
) {
}
