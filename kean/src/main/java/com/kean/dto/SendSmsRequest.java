package com.kean.dto;

import com.kean.utils.QqEmails;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SendSmsRequest(
        // 发码同样要求完整 QQ 邮箱；邮箱允许为空（如改密码场景由服务端取本人邮箱）。
        @Pattern(regexp = QqEmails.OPTIONAL_PATTERN, message = QqEmails.INVALID_MESSAGE)
        String email,

        @NotBlank(message = "请选择验证场景")
        @Pattern(regexp = "^(REGISTER|CHANGE_PASSWORD|FORGOT_PASSWORD|CHANGE_EMAIL)$", message = "验证场景不正确")
        String scene,

        String turnstileToken
) {
}
