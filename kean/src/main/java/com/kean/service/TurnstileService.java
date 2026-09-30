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

@Slf4j
@Service
public class TurnstileService {

    private final TurnstileProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

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
        if (!StringUtils.hasText(properties.getSiteKey())) {
            return new TurnstileConfigVO(true, null);
        }
        return new TurnstileConfigVO(true, properties.getSiteKey());
    }

    public void verifyOrReject(String token, String remoteIp) {
        if (!properties.isEnabled()) {
            return;
        }
        if (!properties.ready()) {
            log.error("Turnstile 已开启但未配置 site-key/secret，拒绝登录");
            throw new BizException(ErrorCode.TURNSTILE_FAILED, "人机验证未配置");
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
