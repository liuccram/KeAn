package com.kean.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kean.im.ImRealtimeRoleService;
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

    /**
     * 阶段 C-4：自研 WS 实时推送的<b>总闸</b>（{@code kean.im.legacy-ws-enabled}，<b>默认 true</b>）。
     *
     * <p>它<b>只影响 {@link #pushMessage}</b>（{@code MESSAGE} 事件）；
     * {@code afterConnectionEstablished} / {@code handleTextMessage} 的 {@code AUTH} 与 {@code PING}/{@code PONG}
     * <b>一律不看它</b> —— 也就是说「握手 / 连接 / 鉴权」这三件事本开关<b>一行都不动</b>：
     * 客户端可能仍然连着自研 WS，只是收不到聊天推送（见 {@link ImRealtimeRoleService} 类注释的边界表）。</p>
     */
    private final ImRealtimeRoleService imRealtimeRoleService;

    private final ScheduledExecutorService authTimeouts = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "chat-ws-auth");
        thread.setDaemon(true);
        return thread;
    });

    public ChatWebSocketHandler(
            ChatSessionHub chatSessionHub,
            ChatAuthService chatAuthService,
            ObjectMapper objectMapper,
            ImRealtimeRoleService imRealtimeRoleService
    ) {
        this.chatSessionHub = chatSessionHub;
        this.chatAuthService = chatAuthService;
        this.objectMapper = objectMapper;
        this.imRealtimeRoleService = imRealtimeRoleService;
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

    /**
     * 推一条 {@code MESSAGE} 事件给指定用户的自研 WS 连接。
     *
     * <p><b>阶段 C-4 新增的一道闸</b>：{@code kean.im.legacy-ws-enabled=false} 时本方法
     * <b>第一行就返回</b>（只记一次计数，不序列化 JSON、不碰任何 session）——
     * 自研 WS 于是「不再承担实时推送职责」，但<b>连接/鉴权照旧</b>（见
     * {@link ImRealtimeRoleService} 类注释）。默认 {@code true} 时走的就是下面原来那段代码，
     * <b>一条语句都不多</b>（只有一次布尔判断）。</p>
     *
     * <p>⚠️ 本方法的<b>签名与参数顺序完全未改</b>（调用点：{@code ChatServiceImpl.send}），
     * 这样开关无论怎么切都不需要改任何调用方。</p>
     */
    public void pushMessage(Long userId, ChatMessageVO vo) {
        if (!imRealtimeRoleService.legacyWsPushEnabled()) {
            // 阶段 C-4：自研 WS 实时推送已按开关停推（消息实时性由 box 的 /im 承担）。
            // 刻意不删代码、只包住：把开关设回 true 即恢复，无需回填。
            imRealtimeRoleService.recordSuppressedPush();
            log.debug("skip legacy ws MESSAGE push ({} disabled), userId={}",
                    ImRealtimeRoleService.PROPERTY_LEGACY_WS_ENABLED, userId);
            return;
        }
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
