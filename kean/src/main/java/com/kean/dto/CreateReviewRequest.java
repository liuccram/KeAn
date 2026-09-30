package com.kean.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateReviewRequest(
        @NotNull(message = "任务不能为空")
        Long taskId,

        @NotNull(message = "请打星")
        @Min(value = 1, message = "评分最少 1 星")
        @Max(value = 5, message = "评分最多 5 星")
        Integer rating,

        @Size(max = 5, message = "最多选择 5 个标签")
        List<String> tags,

        @Size(max = 500, message = "评价最多 500 字")
        String content
) {
}
