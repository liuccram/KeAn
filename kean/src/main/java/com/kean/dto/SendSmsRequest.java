package com.kean.dto;

import com.kean.utils.QqEmails;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SendSmsRequest(
        @Pattern(regexp = QqEmails.OPTIONAL_PATTERN, message = "请填写 5-11 位 QQ 号")
        String email,

        @NotBlank(message = "请选择验证场景")
        @Pattern(regexp = "^(REGISTER|CHANGE_PASSWORD|FORGOT_PASSWORD|CHANGE_EMAIL)$", message = "验证场景不正确")
        String scene,

        String turnstileToken
) {
}
