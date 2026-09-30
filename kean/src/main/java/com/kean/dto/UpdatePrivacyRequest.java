package com.kean.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdatePrivacyRequest(
        @NotNull(message = "请选择隐私设置")
        @Min(value = 0, message = "隐私设置无效")
        @Max(value = 1, message = "隐私设置无效")
        Integer privateAccount
) {
}
