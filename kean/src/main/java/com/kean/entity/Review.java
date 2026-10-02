package com.kean.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kean.support.FieldCipherTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName(value = "review", autoResultMap = true)
public class Review {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private Long fromUserId;

    private Long toUserId;

    private String targetRole;

    private Integer completedFlag;

    private Integer rating;

    private String tagsJson;

    /** 评价文字：敏感文本，落库加密。不要在任何 wrapper 里按它检索/排序。 */
    @TableField(typeHandler = FieldCipherTypeHandler.class)
    private String content;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
