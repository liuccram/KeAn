package com.kean.dto;

public record TaskQuery(
        String keyword,
        String taskDate,
        String timeSlot,
        Long courseId,
        /** 已废弃：校区不再作为筛选条件，服务端不再使用（接口参数保留以免破坏调用方）。 */
        Long campusId,
        String status,
        Long schoolId,
        Long page,
        Long size
) {
}
