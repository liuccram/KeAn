package com.kean.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_session")
public class ChatSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userAId;

    private Long userBId;

    private Long taskId;

    private LocalDateTime lastMessageAt;

    private String lastContent;

    private Integer aUnread;

    private Integer bUnread;

    /**
     * 本会话已分配出去的最大 seq_no，发消息时同步（box 侧会话维度的"最新序号"）。
     * 历史会话为 NULL，客户端未读增量拉取直接用它当游标即可。
     */
    private Long lastSeqNo;

    /**
     * user_a（ID 较小的一方）已读到的 seq_no 位点；NULL 表示还没产生过已读位点。
     */
    private Long aReadSeq;

    /**
     * user_b（ID 较大的一方）已读到的 seq_no 位点；NULL 表示还没产生过已读位点。
     */
    private Long bReadSeq;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
