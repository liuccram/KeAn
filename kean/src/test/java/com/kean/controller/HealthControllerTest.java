package com.kean.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("健康检查端点")
class HealthControllerTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private Connection connection;

    @Test
    @DisplayName("存活探针不依赖任何外部组件，始终返回 UP")
    void liveAlwaysUp() {
        HealthController controller = new HealthController(dataSource, redisTemplate);

        assertThat(controller.live()).containsEntry("status", "UP");
    }

    @Test
    @DisplayName("依赖都正常时返回 200")
    void readyWhenAllHealthy() throws Exception {
        healthyDatabase();
        healthyRedis();
        HealthController controller = new HealthController(dataSource, redisTemplate);

        ResponseEntity<Map<String, Object>> response = controller.ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("status", "UP")
                .containsEntry("db", "UP")
                .containsEntry("redis", "UP");
    }

    @Test
    @DisplayName("Redis 不通时返回 503，且只把 redis 标为 DOWN")
    void readyFailsWhenRedisDown() throws Exception {
        healthyDatabase();
        when(redisTemplate.execute(any(RedisCallback.class))).thenReturn(null);
        HealthController controller = new HealthController(dataSource, redisTemplate);

        ResponseEntity<Map<String, Object>> response = controller.ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody())
                .containsEntry("status", "DOWN")
                .containsEntry("db", "UP")
                .containsEntry("redis", "DOWN");
    }

    @Test
    @DisplayName("取不到数据库连接时返回 503")
    void readyFailsWhenDatabaseUnreachable() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("拒绝连接"));
        healthyRedis();
        HealthController controller = new HealthController(dataSource, redisTemplate);

        ResponseEntity<Map<String, Object>> response = controller.ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody())
                .containsEntry("status", "DOWN")
                .containsEntry("db", "DOWN");
    }

    @Test
    @DisplayName("连接校验不通过也算 DOWN")
    void readyFailsWhenConnectionInvalid() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(1)).thenReturn(false);
        healthyRedis();
        HealthController controller = new HealthController(dataSource, redisTemplate);

        assertThat(controller.ready().getBody()).containsEntry("db", "DOWN");
    }

    @Test
    @DisplayName("依赖悬挂时按超时判 DOWN，不让探针一直等")
    void readyTimesOutOnHangingDependency() throws Exception {
        when(dataSource.getConnection()).thenAnswer(invocation -> {
            Thread.sleep(400);
            return connection;
        });
        healthyRedis();
        HealthController controller = new HealthController(dataSource, redisTemplate, 50);

        ResponseEntity<Map<String, Object>> response = controller.ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody())
                .containsEntry("status", "DOWN")
                .containsEntry("db", "DOWN");
    }

    private void healthyDatabase() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(1)).thenReturn(true);
    }

    @SuppressWarnings("unchecked")
    private void healthyRedis() {
        when(redisTemplate.execute(any(RedisCallback.class))).thenReturn("PONG");
    }
}
