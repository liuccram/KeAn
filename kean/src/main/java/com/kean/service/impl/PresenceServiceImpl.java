package com.kean.service.impl;

import com.kean.service.PresenceService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

@Service
public class PresenceServiceImpl implements PresenceService {

    private static final String PREFIX = "kean:presence:";
    private static final Duration TTL = Duration.ofSeconds(90);

    private final StringRedisTemplate redisTemplate;

    public PresenceServiceImpl(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void heartbeat(Long userId) {
        if (userId == null) {
            return;
        }
        redisTemplate.opsForValue().set(PREFIX + userId, "1", TTL);
    }

    @Override
    public void offline(Long userId) {
        if (userId == null) {
            return;
        }
        redisTemplate.delete(PREFIX + userId);
    }

    @Override
    public long onlineCount() {
        Set<String> keys = redisTemplate.keys(PREFIX + "*");
        return keys == null ? 0 : keys.size();
    }

    @Override
    public boolean isOnline(Long userId) {
        if (userId == null) {
            return false;
        }
        Boolean exists = redisTemplate.hasKey(PREFIX + userId);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public Set<Long> onlineUserIds() {
        Set<String> keys = redisTemplate.keys(PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return Set.of();
        }
        Set<Long> ids = new HashSet<>();
        int prefixLen = PREFIX.length();
        for (String key : keys) {
            if (key == null || key.length() <= prefixLen) {
                continue;
            }
            try {
                ids.add(Long.parseLong(key.substring(prefixLen)));
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return ids;
    }
}
