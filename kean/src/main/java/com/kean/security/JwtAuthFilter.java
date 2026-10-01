package com.kean.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kean.common.ErrorCode;
import com.kean.common.Result;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;
    private final TokenRevokeService tokenRevokeService;
    private final ObjectMapper objectMapper;

    public JwtAuthFilter(
            JwtService jwtService,
            TokenBlacklistService tokenBlacklistService,
            TokenRevokeService tokenRevokeService,
            ObjectMapper objectMapper
    ) {
        this.jwtService = jwtService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.tokenRevokeService = tokenRevokeService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = requestPath(request);
        return "/api/auth/register".equals(path)
                || "/api/auth/login".equals(path)
                || "/api/auth/password/reset".equals(path)
                || "/api/auth/turnstile".equals(path)
                || "/turnstile.html".equals(path)
                || path.startsWith("/ws/")
                || "/error".equals(path);
    }

    private String requestPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            return uri.substring(context.length());
        }
        return uri;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (!StringUtils.hasText(header) || !header.startsWith("Bearer ")) {
            if (isAnonymousAllowed(request)) {
                filterChain.doFilter(request, response);
                return;
            }
            writeUnauthorized(response);
            return;
        }

        String token = header.substring(7);
        try {
            Claims claims = jwtService.parse(token);
            String jti = claims.getId();
            if (tokenBlacklistService.isBlacklisted(jti)) {
                if (isAnonymousAllowed(request)) {
                    filterChain.doFilter(request, response);
                    return;
                }
                writeUnauthorized(response);
                return;
            }
            Long userId = Long.valueOf(claims.getSubject());
            if (tokenRevokeService.isBanned(userId)) {
                writeBanned(response, tokenRevokeService.bannedMessage(userId));
                return;
            }
            if (tokenRevokeService.isRevoked(userId, claims.getIssuedAt() == null ? null : claims.getIssuedAt().toInstant())) {
                if (isAnonymousAllowed(request)) {
                    filterChain.doFilter(request, response);
                    return;
                }
                writeUnauthorized(response);
                return;
            }
            String username = claims.get("username", String.class);
            String role = claims.get("role", String.class);
            LoginUser loginUser = new LoginUser(userId, username, role, jti);
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    loginUser,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role))
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (JwtException | IllegalArgumentException ex) {
            if (isAnonymousAllowed(request)) {
                filterChain.doFilter(request, response);
                return;
            }
            writeUnauthorized(response);
        }
    }

    private boolean isAnonymousAllowed(HttpServletRequest request) {
        String path = requestPath(request);
        if ("POST".equalsIgnoreCase(request.getMethod()) && "/api/auth/sms".equals(path)) {
            return true;
        }
        if ("POST".equalsIgnoreCase(request.getMethod()) && "/api/auth/password/reset".equals(path)) {
            return true;
        }
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        return "/api/schools".equals(path)
                || "/api/provinces".equals(path)
                || "/api/campuses".equals(path)
                || "/api/courses".equals(path)
                || "/api/tasks".equals(path)
                // 必须与 SecurityConfig 的 GET /api/tasks/* 保持一致。此前这里只认 \d+，
                // 于是 /api/tasks/abc 在过滤器就被判 401 —— 前端会当成"登录已失效"把用户
                // 踢回登录页，实际只是 id 格式不对。放开后请求能到达控制器，
                // 由 GlobalExceptionHandler 映射为 400（参数错误）。
                || path.matches("/api/tasks/[^/]+")
                || "/api/announcements/active".equals(path)
                || path.startsWith("/api/files/")
                || "/api/auth/turnstile".equals(path)
                || "/turnstile.html".equals(path)
                || "/health".equals(path)
                || "/health/ready".equals(path);
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(ErrorCode.UNAUTHORIZED.getHttpStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Result.fail(ErrorCode.UNAUTHORIZED));
    }

    private void writeBanned(HttpServletResponse response, String message) throws IOException {
        response.setStatus(ErrorCode.ACCOUNT_BANNED.getHttpStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String text = message == null || message.isBlank()
                ? ErrorCode.ACCOUNT_BANNED.getMessage()
                : message;
        objectMapper.writeValue(response.getWriter(), Result.fail(ErrorCode.ACCOUNT_BANNED, text));
    }
}
