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
@TableName(value = "report", autoResultMap = true)
public class Report {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long reporterId;

    private String targetType;

    private Long targetId;

    private String type;

    /** 举报/反馈正文：敏感文本，落库加密。不要在任何 wrapper 里按它检索/排序。 */
    @TableField(typeHandler = FieldCipherTypeHandler.class)
    private String description;

    private String imagesJson;

    private String status;

    private Long handlerId;

    private String handleResult;

    /** 处理备注：敏感文本，落库加密。不要在任何 wrapper 里按它检索/排序。 */
    @TableField(typeHandler = FieldCipherTypeHandler.class)
    private String handleRemark;

    private LocalDateTime handledAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
