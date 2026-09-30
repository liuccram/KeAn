package com.kean.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateCourseRequest(
        @NotNull(message = "请选择学校")
        Long schoolId,

        @NotBlank(message = "请填写课程代码")
        @Size(max = 64, message = "课程代码最多 64 字")
        String courseCode,

        @NotBlank(message = "请填写课程名称")
        @Size(max = 128, message = "课程名称最多 128 字")
        String courseName
) {
}
