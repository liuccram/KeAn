package com.kean.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateCampusRequest(
        @NotNull(message = "请选择学校")
        Long schoolId,

        @NotBlank(message = "请填写校区名称")
        @Size(max = 100, message = "校区名称最多 100 字")
        String name
) {
}
