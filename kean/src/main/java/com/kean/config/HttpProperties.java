package com.kean.config;

import com.kean.utils.IpUtils;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "kean.http")
public class HttpProperties {

    /**
     * 可信反代地址，逗号分隔，支持 IPv4 CIDR。留空则忽略 X-Forwarded-For / X-Real-IP。
     */
    private String trustedProxies = "";

    @PostConstruct
    public void apply() {
        IpUtils.setTrustedProxies(proxies());
    }

    public List<String> proxies() {
        if (!StringUtils.hasText(trustedProxies)) {
            return List.of();
        }
        return Arrays.stream(trustedProxies.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
