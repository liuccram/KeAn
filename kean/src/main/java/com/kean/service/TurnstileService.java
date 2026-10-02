package com.kean.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kean.common.ErrorCode;
import com.kean.config.TurnstileProperties;
import com.kean.exception.BizException;
import com.kean.vo.TurnstileConfigVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class TurnstileService {

    private final TurnstileProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    /** 配置错误只打一次醒目日志，避免每个请求刷屏。 */
    private final AtomicBoolean configErrorLogged = new AtomicBoolean(false);
    /** 测试密钥告警同样只打一次。 */
    private final AtomicBoolean testKeyLogged = new AtomicBoolean(false);

    public TurnstileService(TurnstileProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public TurnstileConfigVO publicConfig() {
        if (!properties.isEnabled()) {
            return new TurnstileConfigVO(false, null);
        }
        // 测试密钥同样按"未配置"返回：否则前端会渲染一个永远通过的控件，
        // 用户点完提交才拿到 40027，不如直接显示"人机验证未配置"。
        if (!StringUtils.hasText(properties.getSiteKey()) || properties.usesTestKey()) {
            return new TurnstileConfigVO(true, null);
        }
        return new TurnstileConfigVO(true, properties.getSiteKey());
    }

    public void verifyOrReject(String token, String remoteIp) {
        if (!properties.isEnabled()) {
            return;
        }
        if (!properties.ready()) {
            logConfigErrorOnce();
            throw new BizException(ErrorCode.TURNSTILE_NOT_CONFIGURED);
        }
        if (properties.usesTestKey()) {
            // 测试密钥等于没有防护，按配置错误拒绝，且不再调用 Cloudflare
            logTestKeyOnce();
            throw new BizException(ErrorCode.TURNSTILE_NOT_CONFIGURED);
        }
        if (!StringUtils.hasText(token)) {
            throw new BizException(ErrorCode.TURNSTILE_REQUIRED);
        }
        SiteverifyResponse body = siteverify(token.trim(), remoteIp);
        if (body == null || !body.success()) {
            log.warn("Turnstile 校验失败 ip={} codes={}", remoteIp, body == null ? "empty" : body.errorCodes());
            throw new BizException(ErrorCode.TURNSTILE_FAILED);
        }
    }

    /**
     * 密钥缺失属于服务端配置错误，已开启校验时一律拒绝。
     * 只打一次醒目日志（不打印密钥内容），避免每个请求刷屏。
     */
    private void logConfigErrorOnce() {
        if (!configErrorLogged.compareAndSet(false, true)) {
            return;
        }
        log.error("Turnstile 已启用（kean.turnstile.enabled=true）但密钥未配置：site-key {}、secret {}，"
                        + "所有真人验证请求都会被拒绝；请设置环境变量 TURNSTILE_SITE_KEY / TURNSTILE_SECRET，"
                        + "或显式设置 TURNSTILE_ENABLED=false 关闭校验",
                StringUtils.hasText(properties.getSiteKey()) ? "已配置" : "缺失",
                StringUtils.hasText(properties.getSecret()) ? "已配置" : "缺失");
    }

    /**
     * 命中 Cloudflare 官方测试密钥（siteKey 或 secret 之一）：等于没有防护
     * （测试 secret 只认公开的 dummy token），因此按服务端配置错误拒绝，也不会去请求 Cloudflare。
     *
     * <p>注意这不影响本地联调：联调的正规开关是 {@code TURNSTILE_ENABLED=false}，见本方法上方的早返回；
     * 那条路径原样保留。只打一次，且不打印密钥值本身。
     */
    private void logTestKeyOnce() {
        if (!testKeyLogged.compareAndSet(false, true)) {
            return;
        }
        log.error("Turnstile 检测到 Cloudflare 官方测试密钥（site-key 或 secret 是测试值），等于没有防护，"
                + "所有真人验证请求都会被拒绝；请换成真实密钥。"
                + "本地联调请显式设置 TURNSTILE_ENABLED=false 关闭校验，而不是填测试密钥");
    }

    private SiteverifyResponse siteverify(String token, String remoteIp) {
        try {
            StringBuilder form = new StringBuilder();
            form.append("secret=").append(encode(properties.getSecret()));
            form.append("&response=").append(encode(token));
            if (StringUtils.hasText(remoteIp)) {
                form.append("&remoteip=").append(encode(remoteIp));
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getVerifyUrl()))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300 || !StringUtils.hasText(response.body())) {
                log.warn("Turnstile siteverify HTTP {}", response.statusCode());
                return null;
            }
            return objectMapper.readValue(response.body(), SiteverifyResponse.class);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Turnstile siteverify interrupted");
            return null;
        } catch (Exception ex) {
            log.warn("Turnstile siteverify 调用失败: {}", ex.getMessage());
            return null;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record SiteverifyResponse(
            boolean success,
            @JsonProperty("error-codes") List<String> errorCodes
    ) {
    }
}
