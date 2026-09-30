package com.kean.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "kean.cors")
public class CorsProperties {

    private String allowedOrigins = "http://localhost:5173,http://localhost:5174,http://127.0.0.1:5173,http://127.0.0.1:5174";

    @PostConstruct
    public void validate() {
        List<String> origins = origins();
        if (origins.isEmpty()) {
            throw new IllegalStateException("请配置 CORS_ALLOWED_ORIGINS，禁止使用空值或 *");
        }
        for (String origin : origins) {
            if ("*".equals(origin) || origin.contains("*")) {
                throw new IllegalStateException("CORS_ALLOWED_ORIGINS 不允许使用 *，请填写明确的 http(s) 源");
            }
        }
    }

    public List<String> origins() {
        if (!StringUtils.hasText(allowedOrigins)) {
            return List.of();
        }
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
