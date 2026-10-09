package com.kean.dto;

import com.kean.utils.QqEmails;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ChangeEmailRequest(
        @NotBlank(message = QqEmails.INVALID_MESSAGE)
        @Pattern(regexp = QqEmails.REQUIRED_PATTERN, message = QqEmails.INVALID_MESSAGE)
        String email,

        @NotBlank(message = "请填写邮箱验证码")
        @Pattern(regexp = "^\\d{6}$", message = "请填写 6 位验证码")
        String smsCode
) {
}
