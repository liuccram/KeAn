package com.kean.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record HandleReportRequest(
        @NotBlank(message = "请选择处理结果")
        String result,

        @Size(max = 500, message = "备注最多 500 字")
        String remark
) {
}
