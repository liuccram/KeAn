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
