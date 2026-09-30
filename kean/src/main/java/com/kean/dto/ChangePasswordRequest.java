package com.kean.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank(message = "请填写邮箱验证码")
        @Pattern(regexp = "^\\d{6}$", message = "请填写 6 位验证码")
        String smsCode,

        @NotBlank(message = "请填写新密码")
        @Size(min = 8, max = 32, message = "密码长度为 8-32 位")
        String newPassword
) {
}
