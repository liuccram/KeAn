package com.kean.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 敏感文件读取的频率限制。
 *
 * <p>纯单元测试：替换 StringRedisTemplate，不连 Redis。</p>
 */
@ExtendWith(MockitoExtension.class)
class FileRateLimitServiceTest {

    private static final Long USER_ID = 42L;
    private static final String IP = "9.9.9.9";
    private static final String USER_KEY = "kean:file:rl:u:42";
    private static final String IP_KEY = "kean:file:rl:ip:9.9.9.9";

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private FileRateLimitService service;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        service = new FileRateLimitService(redis);
    }

    @Test
    @DisplayName("首次访问放行，并设置 1 分钟窗口")
    void firstReadIsAllowedAndWindowIsSet() {
        when(valueOps.increment(USER_KEY)).thenReturn(1L);

        assertThat(service.allowSensitiveRead(USER_ID, IP)).isTrue();
        verify(redis).expire(USER_KEY, Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("恰好达到上限仍放行")
    void exactlyAtLimitIsAllowed() {
        when(valueOps.increment(USER_KEY)).thenReturn((long) FileRateLimitService.SENSITIVE_PER_MINUTE);

        assertThat(service.allowSensitiveRead(USER_ID, IP)).isTrue();
    }

    @Test
    @DisplayName("超过上限拒绝")
    void overLimitIsDenied() {
        when(valueOps.increment(USER_KEY))
                .thenReturn((long) FileRateLimitService.SENSITIVE_PER_MINUTE + 1L);

        assertThat(service.allowSensitiveRead(USER_ID, IP)).isFalse();
    }

    @Test
    @DisplayName("已登录按 userId 分桶，不同用户互不影响")
    void loggedInUsesUserBucket() {
        when(valueOps.increment("kean:file:rl:u:99")).thenReturn(1L);

        assertThat(service.allowSensitiveRead(99L, IP)).isTrue();
    }

    @Test
    @DisplayName("匿名按 IP 分桶，阈值比登录用户宽")
    void anonymousUsesIpBucketWithWiderLimit() {
        long betweenLimits = (long) FileRateLimitService.SENSITIVE_PER_MINUTE + 50L;
        when(valueOps.increment(IP_KEY)).thenReturn(betweenLimits);

        assertThat(service.allowSensitiveRead(null, IP)).isTrue();
    }

    @Test
    @DisplayName("匿名超过宽阈值仍然拒绝")
    void anonymousOverWideLimitIsDenied() {
        when(valueOps.increment(IP_KEY))
                .thenReturn((long) FileRateLimitService.SENSITIVE_PER_MINUTE_ANONYMOUS + 1L);

        assertThat(service.allowSensitiveRead(null, IP)).isFalse();
    }

    @Test
    @DisplayName("IP 缺失时归一化为 unknown，避免计数落到空键上")
    void missingIpFallsBackToUnknownBucket() {
        when(valueOps.increment("kean:file:rl:ip:unknown")).thenReturn(1L);

        assertThat(service.allowSensitiveRead(null, null)).isTrue();
    }
}
