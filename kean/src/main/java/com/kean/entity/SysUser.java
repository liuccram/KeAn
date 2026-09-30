package com.kean.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String role;

    private String username;

    private String phone;

    private String email;

    private String passwordHash;

    private String nickname;

    private String gender;

    private String avatarUrl;

    private String coverUrl;

    private Long schoolId;

    private Long campusId;

    private Integer schoolChangeCount;

    private Integer completedCount;

    private BigDecimal ratingAvg;

    private Integer ratingCount;

    private Integer publishCompletedCount;

    private BigDecimal publishRatingAvg;

    private Integer publishRatingCount;

    private BigDecimal applyRatingAvg;

    private Integer applyRatingCount;

    private Integer cancelledCount;

    private Integer reportedCount;

    private String status;

    private Integer forbidPublish;

    private LocalDateTime forbidPublishUntil;

    private Integer forbidApply;

    private LocalDateTime forbidApplyUntil;

    private Integer muted;

    private Integer mustChangePassword;

    private LocalDateTime mutedUntil;

    private Integer privateAccount;

    private LocalDateTime lastLoginAt;

    private String lastLoginIp;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
