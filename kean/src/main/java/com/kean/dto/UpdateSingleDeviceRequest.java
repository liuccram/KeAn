package com.kean.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 「仅允许一台设备在线」开关。写法与 {@link UpdatePrivacyRequest} 保持一致。
 */
public record UpdateSingleDeviceRequest(
        @NotNull(message = "请选择设备登录设置")
        @Min(value = 0, message = "设备登录设置无效")
        @Max(value = 1, message = "设备登录设置无效")
        Integer singleDevice
) {
}
