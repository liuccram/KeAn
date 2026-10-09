package com.kean.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_message")
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    /**
     * 会话内消息序号，单会话内连续递增（box-im {@code im_private_message.seq_no}）。
     * 分配方式见 {@link com.kean.service.ChatSeqService}。历史行可能为 NULL。
     */
    private Long seqNo;

    /**
     * 业务幂等 id，由客户端生成（box-im {@code im_private_message.local_id varchar(32)}）；
     * 服务端未收到时自行生成。与 sender_id 组成唯一索引，重复提交返回已存在的那条。
     */
    private String localId;

    private Long senderId;

    private String msgType;

    private String content;

    /**
     * 消息状态，数值语义对齐 box：0 未读 / 1 已发送 / 2 撤回 / 3 已读。
     * 见 {@link com.kean.enums.ChatMessageStatus}。库里该列 NOT NULL DEFAULT 1。
     */
    private Integer status;

    /**
     * 接收方读取时间；未读或历史行为 NULL。仅在被置为 3（已读）时写入。
     */
    private LocalDateTime readAt;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
