package com.kean.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kean.vo.ChatMessageVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final long AUTH_TIMEOUT_SECONDS = 5;

    private final ChatSessionHub chatSessionHub;
    private final ChatAuthService chatAuthService;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService authTimeouts = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "chat-ws-auth");
        thread.setDaemon(true);
        return thread;
    });

    public ChatWebSocketHandler(
            ChatSessionHub chatSessionHub,
            ChatAuthService chatAuthService,
            ObjectMapper objectMapper
    ) {
        this.chatSessionHub = chatSessionHub;
        this.chatAuthService = chatAuthService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = userId(session);
        if (userId != null) {
            chatSessionHub.register(userId, session);
            return;
        }
        authTimeouts.schedule(() -> {
            if (session.isOpen() && userId(session) == null) {
                closeQuietly(session, CloseStatus.NOT_ACCEPTABLE);
            }
        }, AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String type = node.path("type").asText("");
            if ("AUTH".equals(type)) {
                handleAuth(session, node.path("token").asText(""));
                return;
            }
            Long userId = userId(session);
            if (userId == null) {
                closeQuietly(session, CloseStatus.NOT_ACCEPTABLE);
                return;
            }
            if ("PING".equals(type)) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of("type", "PONG"))));
            }
        } catch (Exception ex) {
            log.debug("ignore ws payload", ex);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = userId(session);
        if (userId != null) {
            chatSessionHub.unregister(userId, session);
        }
    }

    public void pushMessage(Long userId, ChatMessageVO vo) {
        try {
            chatSessionHub.sendTo(userId, objectMapper.writeValueAsString(Map.of(
                    "type", "MESSAGE",
                    "data", vo
            )));
        } catch (Exception ex) {
            log.debug("push chat message failed userId={}", userId, ex);
        }
    }

    private void handleAuth(WebSocketSession session, String token) {
        if (userId(session) != null) {
            return;
        }
        if (!StringUtils.hasText(token)) {
            closeQuietly(session, CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        Long userId = chatAuthService.authenticate(token);
        if (userId == null) {
            closeQuietly(session, CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        session.getAttributes().put(ChatHandshakeInterceptor.ATTR_USER_ID, userId);
        chatSessionHub.register(userId, session);
    }

    private Long userId(WebSocketSession session) {
        Object value = session.getAttributes().get(ChatHandshakeInterceptor.ATTR_USER_ID);
        if (value instanceof Long id) {
            return id;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (Exception ignored) {
            // ignore
        }
    }
}
