package com.kean.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record UpdateConfigRequest(
        @NotEmpty(message = "请提交配置项")
        List<ConfigItem> items
) {
    public record ConfigItem(String key, String value) {
    }
}
