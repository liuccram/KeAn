package com.kean.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminUserStatusRequest(
        @NotBlank(message = "请选择状态")
        @Pattern(regexp = "^(NORMAL|BANNED)$", message = "状态仅支持 NORMAL 或 BANNED")
        String status,

        @Size(max = 255, message = "备注最多 255 字")
        String remark
) {
}
