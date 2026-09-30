package com.kean.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@TableName("substitute_task")
public class SubstituteTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long publisherId;

    private Long courseId;

    private String courseNameSnapshot;

    private LocalDate taskDate;

    private LocalTime startTime;

    private LocalTime endTime;

    private LocalDateTime startAt;

    private LocalDateTime endAt;

    private Long schoolId;

    private Long campusId;

    private String building;

    private String classroom;

    private Integer computerLab;

    private Integer requirePhoto;

    private String genderRequirement;

    private BigDecimal reward;

    private String reason;

    private String requirement;

    private String remark;

    private String status;

    private Integer applyCount;

    private Long acceptedApplicationId;

    private Integer publisherConfirmed;

    private Integer applicantConfirmed;

    private String fulfillPhotoKey;

    private Integer publisherCompleted;

    private Integer applicantCompleted;

    private String cancelReason;

    private String cancelledBy;

    private Integer photoReminded;

    private Integer classReminded;

    private Integer completeReminded;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
