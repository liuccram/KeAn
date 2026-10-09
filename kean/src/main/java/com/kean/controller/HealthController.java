package com.kean.controller;

import com.kean.im.ImQueueMonitorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * 探活与就绪端点，供容器编排 / 负载均衡 / 运维使用。
 *
 * <p>两个端点都<b>免鉴权</b>（探针不会带 token），因此响应体只给组件名与 UP/DOWN，
 * 不返回异常信息、主机名或连接串 —— 细节只进日志，避免对外暴露内部结构。
 *
 * <p>职责刻意分成两个，因为它们的失败语义相反：
 * <ul>
 *   <li>{@code GET /health} —— 存活探针。只要进程还能处理请求就返回 200，<b>不检查任何依赖</b>。
 *       若它也检查数据库，一次数据库抖动就会被判定为"进程已死"，容器被反复重启，
 *       反而放大故障。</li>
 *   <li>{@code GET /health/ready} —— 就绪探针。检查 MySQL 与 Redis，任一不可用返回 503，
 *       让负载均衡把流量摘走。它只影响"是否接流量"，不影响"是否重启"。</li>
 * </ul>
 *
 * <p>对象存储<b>故意不参与判定</b>：它不可用时多数接口仍能正常服务（只有图片相关功能受影响），
 * 把它算进就绪会让一次存储抖动把整个实例摘下线。存储状态由启动期连通性探测日志
 * 与上传报错体现，不作为就绪依据。
 *
 * <p><b>IM 镜像投递同理，也不参与判定</b>（第 ④ 项监控）：{@code /health/ready} 的返回里
 * <b>追加</b>一个 {@code im} 字段，取值 {@code DISABLED} / {@code UP} / {@code DEGRADED}
 * （由 {@code ImQueueMonitorService} 的巡检结果决定），但
 * <b>HTTP 状态码与既有 {@code db} / {@code redis} 字段的语义完全不变</b>：
 * IM 是「尽力而为的旁路」，即使它的队列堆到几万条，kean 的其余接口依然完全可用，
 * 把整站判成不健康（503 → 被负载均衡摘流量 → 连锁雪崩）是错误的处置。
 * 需要按 IM 状态告警的编排系统请读 {@code im} 字段本身，而不是看状态码。
 */
@RestController
public class HealthController {

    /** 探针超时预算：连接池等待与 Redis 命令超时都可能长达数十秒，不能让它拖住探针。 */
    private static final long DEFAULT_PROBE_TIMEOUT_MS = 2000L;

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final DataSource dataSource;
    private final StringRedisTemplate redisTemplate;
    private final long probeTimeoutMs;

    /**
     * IM 巡检状态来源（第 ④ 项）。<b>可选依赖</b>，用 {@code @Autowired(required = false)}
     * 的 setter 注入而不是构造参数，原因有二：
     * <ol>
     *   <li>本类的两个构造函数被 {@code HealthControllerTest} 直接调用，
     *       改签名会让既有单测编译失败（本轮不许破坏既有验证）；</li>
     *   <li>IM 是旁路：即使这个 Bean 不存在（例如裁剪过的上下文 / 单测），
     *       {@code /health/ready} 也必须照常工作。</li>
     * </ol>
     * 它为 {@code null} 时，{@code im} 字段按 {@code DISABLED} 处理。
     */
    private ImQueueMonitorService imQueueMonitor;

    /**
     * 显式标注 {@code @Autowired}：本类还有一个包级私有的构造函数（供测试注入更短的超时），
     * 两个构造函数会让 Spring 无法判断用哪个，从而直接启动失败。
     */
    @Autowired
    public HealthController(DataSource dataSource, StringRedisTemplate redisTemplate) {
        this(dataSource, redisTemplate, DEFAULT_PROBE_TIMEOUT_MS);
    }

    HealthController(DataSource dataSource, StringRedisTemplate redisTemplate, long probeTimeoutMs) {
        this.dataSource = dataSource;
        this.redisTemplate = redisTemplate;
        this.probeTimeoutMs = probeTimeoutMs;
    }

    /** IM 巡检组件的可选注入（见字段注释）。 */
    @Autowired(required = false)
    public void setImQueueMonitor(ImQueueMonitorService imQueueMonitor) {
        this.imQueueMonitor = imQueueMonitor;
    }

    @GetMapping("/health")
    public Map<String, Object> live() {
        return Map.of("status", "UP");
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        boolean db = probe("db", this::checkDatabase);
        boolean redis = probe("redis", this::checkRedis);
        boolean up = db && redis;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", up ? "UP" : "DOWN");
        body.put("db", db ? "UP" : "DOWN");
        body.put("redis", redis ? "UP" : "DOWN");
        // 第 ④ 项：追加 IM 状态。只反映状态，绝不参与 status/HTTP 码的判定（见类注释）。
        body.put("im", imStatus());
        return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /**
     * IM 状态：{@code DISABLED}（密钥未配置/不合格，或镜像投递已关）/
     * {@code UP}（启用且最近一次巡检无异常）/ {@code DEGRADED}（启用但队列堆积、
     * 投递失败超阈值或发现残留队列）。
     *
     * <p>纯内存读、不做任何 I/O，因此<b>不会</b>拖慢探针，也不需要走 {@link #probe} 的超时预算。</p>
     */
    private String imStatus() {
        if (imQueueMonitor == null) {
            return ImQueueMonitorService.STATUS_DISABLED;
        }
        try {
            return imQueueMonitor.healthStatus();
        } catch (Exception ex) {
            // 理论上不可达（healthStatus 只读 volatile）。真出问题也绝不能让探针失败。
            log.warn("读取 IM 巡检状态失败（IM 是旁路，不影响就绪判定）：{}", ex.getMessage());
            return ImQueueMonitorService.STATUS_DISABLED;
        }
    }

    private void checkDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.isValid(1)) {
                throw new SQLException("连接校验未通过");
            }
        }
    }

    private void checkRedis() {
        String pong = redisTemplate.execute((RedisCallback<String>) RedisConnection::ping);
        if (pong == null) {
            throw new IllegalStateException("PING 无响应");
        }
    }

    /**
     * 带超时地跑一项检查。超时即视为 DOWN —— 探针有自己的超时预算，
     * 不能因为某个依赖悬挂就一直等下去。
     */
    private boolean probe(String name, Probe probe) {
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    probe.run();
                } catch (Exception ex) {
                    throw new CompletionException(ex);
                }
            }).orTimeout(probeTimeoutMs, TimeUnit.MILLISECONDS).join();
            return true;
        } catch (Exception ex) {
            log.warn("就绪检查未通过 [{}]：{}", name, rootCauseMessage(ex));
            return false;
        }
    }

    private static String rootCauseMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    @FunctionalInterface
    private interface Probe {
        void run() throws Exception;
    }
}
