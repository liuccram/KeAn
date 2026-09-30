package com.kean.chat;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.List;
import java.util.Map;

@Component
public class ChatHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USER_ID = "userId";
    public static final String PROTOCOL = "kean";
    private static final String PROTOCOL_HEADER = "Sec-WebSocket-Protocol";

    private final ChatAuthService chatAuthService;

    public ChatHandshakeInterceptor(ChatAuthService chatAuthService) {
        this.chatAuthService = chatAuthService;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        echoProtocol(request, response);
        String token = resolveToken(request);
        if (!StringUtils.hasText(token)) {
            return true;
        }
        Long userId = chatAuthService.authenticate(token);
        if (userId == null) {
            return false;
        }
        attributes.put(ATTR_USER_ID, userId);
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
        // no-op
    }

    private String resolveToken(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    private void echoProtocol(ServerHttpRequest request, ServerHttpResponse response) {
        List<String> requested = request.getHeaders().get(PROTOCOL_HEADER);
        if (requested == null || requested.isEmpty()) {
            return;
        }
        for (String raw : requested) {
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            for (String item : raw.split(",")) {
                if (PROTOCOL.equalsIgnoreCase(item.trim())) {
                    response.getHeaders().set(PROTOCOL_HEADER, PROTOCOL);
                    return;
                }
            }
        }
    }
}
