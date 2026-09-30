package com.kean.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateSchoolRequest(
        @NotBlank(message = "请填写学校名称")
        @Size(max = 100, message = "学校名称最多 100 字")
        String name,

        @NotNull(message = "请选择省份")
        Long provinceId
) {
}
