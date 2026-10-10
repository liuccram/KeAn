package com.kean.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notification")
public class Notification {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String type;

    private String title;

    private String content;

    private String bizType;

    private Long bizId;

    /**
     * 收件角色：PUBLISHER / APPLICANT（取值与 review.target_role 一致，见 V36）。
     * 可空 —— 只标注"同一事件同时发给两方"的那几类通知；
     * 系统通知、申请通知等单一收件角色的通知一律为 null（历史行为同样为 null）。
     */
    private String receiverRole;

    private Integer readFlag;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
