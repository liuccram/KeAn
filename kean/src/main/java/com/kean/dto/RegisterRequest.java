package com.kean.dto;

import com.kean.utils.QqEmails;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(min = 4, max = 32, message = "用户名长度为 4-32 位")
        @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名仅支持字母、数字和下划线")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 32, message = "密码长度为 8-32 位")
        String password,

        @NotBlank(message = "昵称不能为空")
        @Size(min = 1, max = 32, message = "昵称长度为 1-32 位")
        String nickname,

        @NotBlank(message = "请选择性别")
        @Pattern(regexp = "^(MALE|FEMALE)$", message = "性别仅支持男或女")
        String gender,

        @NotNull(message = "学校不能为空")
        Long schoolId,

        @NotNull(message = "校区不能为空")
        Long campusId,

        @NotBlank(message = "请填写 QQ 号")
        @Pattern(regexp = QqEmails.REQUIRED_PATTERN, message = "请填写 5-11 位 QQ 号")
        String email,

        @NotBlank(message = "请填写邮箱验证码")
        @Pattern(regexp = "^\\d{6}$", message = "请填写 6 位验证码")
        String smsCode,

        String turnstileToken
) {
}
