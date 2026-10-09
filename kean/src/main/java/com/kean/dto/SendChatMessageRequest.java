package com.kean.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发送私聊消息请求。
 *
 * <p>消息类型字段同时接受 {@code msgType}（课安/uni-kean 现有写法）和 {@code type}
 * （本轮冻结契约里的写法），两者都不会 400。两个都传时以 {@code type} 为准。
 *
 * <p>{@code localId} 为可选字段（对照 box-im：local_id 由前端生成，varchar(32)）。
 * 老客户端不带它也能照旧发送：服务端会自己生成一个，保证
 * {@code uk_chat_message_sender_local (sender_id, local_id)} 不冲突；
 * 新客户端带上它时，同一 (senderId, localId) 重复提交会返回已存在的那条消息（幂等重发）。
 */
public record SendChatMessageRequest(
        String msgType,

        /*
         * 兼容契约里的 `type` 字段名。放在 msgType 之后，所以构造器里 type 是第 2 个入参；
         * 两个字段都被 Jackson 填值时，由 type() 优先取用（见 ChatController）。
         */
        @JsonAlias("type")
        String type,

        @NotBlank(message = "消息不能为空")
        @Size(max = 2000, message = "消息最多 2000 字")
        String content,

        @Size(max = 32, message = "localId 最多 32 个字符")
        String localId
) {

    /**
     * 实际生效的消息类型：优先 {@code type}（冻结晶契约），其次 {@code msgType}（老客户端）。
     */
    public String resolvedMsgType() {
        return type != null && !type.isBlank() ? type : msgType;
    }
}
