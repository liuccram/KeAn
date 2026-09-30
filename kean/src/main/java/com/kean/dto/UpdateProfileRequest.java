package com.kean.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @NotBlank(message = "昵称不能为空")
        @Size(min = 1, max = 32, message = "昵称长度为 1-32 位")
        String nickname,

        @NotBlank(message = "请选择性别")
        @Pattern(regexp = "^(MALE|FEMALE)$", message = "性别仅支持男或女")
        String gender,

        @NotNull(message = "学校不能为空")
        Long schoolId,

        @NotNull(message = "校区不能为空")
        Long campusId
) {
}
