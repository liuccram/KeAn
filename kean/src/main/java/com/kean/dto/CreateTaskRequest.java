package com.kean.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public record CreateTaskRequest(
        @NotBlank(message = "请填写课程名称")
        @Size(max = 128)
        String courseName,
        @NotNull(message = "请选择上课日期")
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate taskDate,
        @NotNull(message = "请选择开始时间")
        @JsonFormat(pattern = "HH:mm")
        LocalTime startTime,
        @NotNull(message = "请选择结束时间")
        @JsonFormat(pattern = "HH:mm")
        LocalTime endTime,
        @NotNull(message = "请选择校区") Long campusId,
        @NotBlank(message = "请填写教学楼") @Size(max = 64) String building,
        @NotBlank(message = "请填写教室") @Size(max = 64) String classroom,
        @NotNull(message = "请选择是否上机")
        Boolean computerLab,
        Boolean requirePhoto,
        @NotBlank(message = "请选择性别要求")
        @Pattern(regexp = "^(ANY|MALE|FEMALE)$", message = "性别要求仅支持不限/男/女")
        String genderRequirement,
        @NotNull(message = "请填写酬谢金额") @DecimalMin(value = "0.00", message = "酬谢金额不能为负")
        BigDecimal reward,
        @Size(max = 500) String reason,
        @Size(max = 500) String requirement,
        @Size(max = 500) String remark
) {
}
