package com.kean.security;

import com.kean.config.JwtProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class TokenRevokeService {

    private static final String KEY_PREFIX = "kean:user:revoke:";
    private static final String BAN_PREFIX = "kean:user:banned:";

    private final StringRedisTemplate redisTemplate;
    private final JwtProperties jwtProperties;

    public TokenRevokeService(StringRedisTemplate redisTemplate, JwtProperties jwtProperties) {
        this.redisTemplate = redisTemplate;
        this.jwtProperties = jwtProperties;
    }

    public void revoke(Long userId) {
        if (userId == null) {
            return;
        }
        long now = Instant.now().getEpochSecond();
        redisTemplate.opsForValue().set(
                KEY_PREFIX + userId,
                String.valueOf(now),
                ttl()
        );
    }

    public void markBanned(Long userId, String message) {
        if (userId == null) {
            return;
        }
        String text = message == null || message.isBlank() ? "你的账号因违规已被封禁，如有疑问请联系平台。" : message.trim();
        redisTemplate.opsForValue().set(BAN_PREFIX + userId, text, ttl());
    }

    public void clearBanned(Long userId) {
        if (userId == null) {
            return;
        }
        redisTemplate.delete(BAN_PREFIX + userId);
    }

    public boolean isBanned(Long userId) {
        if (userId == null) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(BAN_PREFIX + userId));
    }

    public String bannedMessage(Long userId) {
        if (userId == null) {
            return null;
        }
        return redisTemplate.opsForValue().get(BAN_PREFIX + userId);
    }

    private Duration ttl() {
        return Duration.ofSeconds(Math.max(jwtProperties.getExpireSeconds(), 60));
    }

    public boolean isRevoked(Long userId, Instant issuedAt) {
        if (userId == null || issuedAt == null) {
            return false;
        }
        String raw = redisTemplate.opsForValue().get(KEY_PREFIX + userId);
        if (raw == null) {
            return false;
        }
        try {
            long revokeAt = Long.parseLong(raw);
            return issuedAt.getEpochSecond() <= revokeAt;
        } catch (NumberFormatException ex) {
            return false;
        }
    }
}
