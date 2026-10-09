package com.kean.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class RealtimePublisher {

    private final ChatSessionHub chatSessionHub;
    private final ObjectMapper objectMapper;

    public RealtimePublisher(ChatSessionHub chatSessionHub, ObjectMapper objectMapper) {
        this.chatSessionHub = chatSessionHub;
        this.objectMapper = objectMapper;
    }

    public void notice(Long userId, String noticeType, String bizType, Long bizId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "NOTICE");
        payload.put("noticeType", noticeType == null ? "" : noticeType);
        payload.put("bizType", bizType == null ? "" : bizType);
        payload.put("bizId", bizId == null ? 0 : bizId);
        send(userId, payload);
    }

    /**
     * 已读回执（本轮新增的事件类型）。推给<b>发送方</b>：告诉它"你的消息已经被对方读到 maxSeq 了"。
     *
     * <p>payload 字段（与 NOTICE 一样扁平放在顶层，不套 data）：
     * <pre>
     * {
     *   "type": "READ",
     *   "sessionId": 12,
     *   "maxSeq": 35,
     *   "readerId": 7
     * }
     * </pre>
     * {@code maxSeq} 是对方读到的位点，{@code readerId} 是读消息的人。
     * 事件在 markRead 的数据库写入过程中直推（与既有 MESSAGE 推送一样不额外等待事务提交），
     * 对方不在线时由 {@link #send} 静默丢弃，库里已读位点仍然是准确的。
     */
    public void read(Long userId, Long sessionId, Long maxSeq, Long readerId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "READ");
        payload.put("sessionId", sessionId == null ? 0 : sessionId);
        payload.put("maxSeq", maxSeq == null ? 0 : maxSeq);
        payload.put("readerId", readerId == null ? 0 : readerId);
        send(userId, payload);
    }

    public void send(Long userId, Map<String, Object> payload) {
        if (userId == null || payload == null || payload.isEmpty()) {
            return;
        }
        try {
            chatSessionHub.sendTo(userId, objectMapper.writeValueAsString(payload));
        } catch (Exception ignored) {
            // 用户不在线时只保留库内通知
        }
    }
}
