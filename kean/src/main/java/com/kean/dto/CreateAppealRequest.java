package com.kean.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateAppealRequest(
        @Size(max = 500, message = "申诉说明最多 500 字")
        String content,

        @Size(max = 3, message = "最多上传 3 张图片")
        List<String> images
) {
}
