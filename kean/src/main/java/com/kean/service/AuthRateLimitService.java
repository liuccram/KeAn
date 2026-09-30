package com.kean.service;

import com.kean.common.ErrorCode;
import com.kean.exception.BizException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;

@Service
public class AuthRateLimitService {

    private static final int LOGIN_USER_FAILS = 5;
    private static final int LOGIN_IP_FAILS = 20;
    private static final Duration LOGIN_WINDOW = Duration.ofMinutes(15);
    private static final int SMS_IP_DAILY = 40;
    private static final Duration SMS_IP_COOLDOWN = Duration.ofSeconds(10);

    private final StringRedisTemplate redis;

    public AuthRateLimitService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void assertLoginAllowed(String ip, String username) {
        if (hasKey("kean:login:lock:u:" + normUser(username)) || hasKey("kean:login:lock:ip:" + normIp(ip))) {
            throw new BizException(ErrorCode.LOGIN_LOCKED);
        }
    }

    public void recordLoginFailure(String ip, String username) {
        bumpLock("kean:login:fail:u:" + normUser(username), "kean:login:lock:u:" + normUser(username), LOGIN_USER_FAILS);
        bumpLock("kean:login:fail:ip:" + normIp(ip), "kean:login:lock:ip:" + normIp(ip), LOGIN_IP_FAILS);
    }

    public void clearLoginFailures(String ip, String username) {
        redis.delete("kean:login:fail:u:" + normUser(username));
        redis.delete("kean:login:lock:u:" + normUser(username));
        redis.delete("kean:login:fail:ip:" + normIp(ip));
        redis.delete("kean:login:lock:ip:" + normIp(ip));
    }

    public void assertSmsAllowed(String ip) {
        String normalized = normIp(ip);
        String cdKey = "kean:sms:ip:cd:" + normalized;
        if (Boolean.TRUE.equals(redis.hasKey(cdKey))) {
            throw new BizException(ErrorCode.SMS_TOO_FREQUENT);
        }
        String dayKey = "kean:sms:ip:day:" + LocalDate.now() + ":" + normalized;
        long used = increment(dayKey, Duration.ofHours(26));
        if (used > SMS_IP_DAILY) {
            throw new BizException(ErrorCode.SMS_TOO_FREQUENT, "今日发送次数过多，请稍后再试");
        }
        redis.opsForValue().set(cdKey, "1", SMS_IP_COOLDOWN);
    }

    private void bumpLock(String failKey, String lockKey, int maxFails) {
        long fails = increment(failKey, LOGIN_WINDOW);
        if (fails >= maxFails) {
            redis.opsForValue().set(lockKey, "1", LOGIN_WINDOW);
        }
    }

    private long increment(String key, Duration ttl) {
        Long value = redis.opsForValue().increment(key);
        long count = value == null ? 1 : value;
        if (count == 1) {
            redis.expire(key, ttl);
        }
        return count;
    }

    private boolean hasKey(String key) {
        return Boolean.TRUE.equals(redis.hasKey(key));
    }

    private static String normUser(String username) {
        return StringUtils.hasText(username) ? username.trim().toLowerCase() : "-";
    }

    private static String normIp(String ip) {
        return StringUtils.hasText(ip) ? ip.trim() : "unknown";
    }
}
