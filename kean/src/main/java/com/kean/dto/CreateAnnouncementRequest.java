package com.kean.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAnnouncementRequest(
        @NotBlank(message = "请填写标题")
        @Size(max = 100, message = "标题最多 100 字")
        String title,

        @NotBlank(message = "请填写正文")
        @Size(max = 2000, message = "正文最多 2000 字")
        String content,

        @NotBlank(message = "请选择范围")
        @Pattern(regexp = "^(ALL|SCHOOL)$", message = "范围仅支持 ALL 或 SCHOOL")
        String scope,

        Long schoolId,

        Boolean publish
) {
}
