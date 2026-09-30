package com.kean.chat;

import com.kean.security.JwtService;
import com.kean.security.TokenBlacklistService;
import com.kean.security.TokenRevokeService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;

@Service
public class ChatAuthService {

    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;
    private final TokenRevokeService tokenRevokeService;

    public ChatAuthService(
            JwtService jwtService,
            TokenBlacklistService tokenBlacklistService,
            TokenRevokeService tokenRevokeService
    ) {
        this.jwtService = jwtService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.tokenRevokeService = tokenRevokeService;
    }

    public Long authenticate(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        try {
            Claims claims = jwtService.parse(token.trim());
            if (tokenBlacklistService.isBlacklisted(claims.getId())) {
                return null;
            }
            Long userId = Long.valueOf(claims.getSubject());
            if (tokenRevokeService.isBanned(userId)) {
                return null;
            }
            Instant issuedAt = claims.getIssuedAt() == null ? null : claims.getIssuedAt().toInstant();
            if (tokenRevokeService.isRevoked(userId, issuedAt)) {
                return null;
            }
            return userId;
        } catch (JwtException | IllegalArgumentException ex) {
            return null;
        }
    }
}
