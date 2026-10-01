package com.kean.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 文件读取频率限制。
 *
 * <p>只作用于<b>敏感目录</b>（report / appeal / chat / fulfill）。头像与封面量最大、风险最低，
 * 不参与限制，以免正常列表渲染被误伤。</p>
 *
 * <p>计数维度：已登录按 userId，匿名按客户端 IP。匿名阈值更宽，因为校园网常有多人共用出口 IP。</p>
 *
 * <p>阈值是固定窗口（1 分钟）。这里刻意取得比较宽：一次聊天页渲染就可能同时加载几十张图片，
 * 阈值过紧会让正常滚动出现 429。</p>
 */
@Service
public class FileRateLimitService {

    /** 已登录用户：每分钟允许的敏感文件读取次数。 */
    static final int SENSITIVE_PER_MINUTE = 240;

    /** 匿名请求：按 IP 计数，阈值放宽以容忍 NAT 后的多用户。 */
    static final int SENSITIVE_PER_MINUTE_ANONYMOUS = 480;

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final String USER_PREFIX = "kean:file:rl:u:";
    private static final String IP_PREFIX = "kean:file:rl:ip:";

    private final StringRedisTemplate redis;

    public FileRateLimitService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 记一次敏感文件读取并判断是否放行。
     *
     * @param userId 当前登录用户；匿名为 {@code null}
     * @param ip     客户端 IP，用于匿名计数
     * @return 未超限返回 true
     */
    public boolean allowSensitiveRead(Long userId, String ip) {
        boolean loggedIn = userId != null;
        String key = loggedIn ? USER_PREFIX + userId : IP_PREFIX + normIp(ip);
        int max = loggedIn ? SENSITIVE_PER_MINUTE : SENSITIVE_PER_MINUTE_ANONYMOUS;

        Long value = redis.opsForValue().increment(key);
        long count = value == null ? 1L : value;
        if (count == 1L) {
            redis.expire(key, WINDOW);
        }
        return count <= max;
    }

    private static String normIp(String ip) {
        return StringUtils.hasText(ip) ? ip.trim() : "unknown";
    }
}
