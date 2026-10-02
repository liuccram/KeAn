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
@TableName(value = "report_appeal", autoResultMap = true)
public class ReportAppeal {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long reportId;

    private Long userId;

    /** 申诉正文：敏感文本，落库加密。不要在任何 wrapper 里按它检索/排序。 */
    @TableField(typeHandler = FieldCipherTypeHandler.class)
    private String content;

    private String imagesJson;

    private String status;

    private Long handlerId;

    /** 处理备注：敏感文本，落库加密。不要在任何 wrapper 里按它检索/排序。 */
    @TableField(typeHandler = FieldCipherTypeHandler.class)
    private String handleRemark;

    private LocalDateTime handledAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
