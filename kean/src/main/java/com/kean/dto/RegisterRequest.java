package com.kean.dto;

import com.kean.utils.QqEmails;
import jakarta.validation.constraints.Email;
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

        // 昵称选填：留空（null/空白）时由 AuthServiceImpl 用雪花算法自动分配「课安用户xxxxxx」。
        @Size(max = 32, message = "昵称最长 32 位")
        String nickname,

        @NotBlank(message = "请选择性别")
        @Pattern(regexp = "^(MALE|FEMALE)$", message = "性别仅支持男或女")
        String gender,

        @NotNull(message = "学校不能为空")
        Long schoolId,

        // 校区改为用户手输文本（选填）：不填（null/空白）也能注册；学校仍必填。
        @Size(max = 50, message = "校区名称最长 50 个字")
        String campusText,

        // 必须是完整 QQ 邮箱（12345678@qq.com）：只填 QQ 号、缺 @、非 qq.com 域名一律拒绝。
        // @Email 与 @Pattern 都用同一个提示文案，谁先命中提示都一样；@Pattern 才是真正卡住"纯 QQ 号"的那道关。
        @NotBlank(message = QqEmails.INVALID_MESSAGE)
        @Email(message = QqEmails.INVALID_MESSAGE)
        @Pattern(regexp = QqEmails.REQUIRED_PATTERN, message = QqEmails.INVALID_MESSAGE)
        String email,

        @NotBlank(message = "请填写邮箱验证码")
        @Pattern(regexp = "^\\d{6}$", message = "请填写 6 位验证码")
        String smsCode,

        String turnstileToken
) {
}
