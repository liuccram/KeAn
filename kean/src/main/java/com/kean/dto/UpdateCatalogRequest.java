package com.kean.dto;

import jakarta.validation.constraints.Size;

public record UpdateCatalogRequest(
        @Size(max = 100, message = "名称最多 100 字")
        String name,

        Long provinceId,

        Integer status,

        @Size(max = 64, message = "课程代码最多 64 字")
        String courseCode,

        @Size(max = 128, message = "课程名称最多 128 字")
        String courseName
) {
}
