package com.kean.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateReportRequest(
        @NotBlank(message = "请选择类型")
        String targetType,

        Long targetId,

        @NotBlank(message = "请选择原因")
        String type,

        @Size(max = 500, message = "说明最多 500 字")
        String description,

        @Size(max = 3, message = "最多上传 3 张图片")
        List<String> images
) {
}
