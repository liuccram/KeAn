package com.kean.dto;

import jakarta.validation.constraints.NotNull;

public record FavoriteRequest(
        @NotNull(message = "请选择代课任务")
        Long taskId
) {
}
