package com.kean.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_blacklist")
public class UserBlacklist {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long blockedUserId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
