package com.kean.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@Getter
@Setter
@ConfigurationProperties(prefix = "kean.turnstile")
public class TurnstileProperties {

    /**
     * 为 true 时登录必须通过 Cloudflare Turnstile；密钥未配则拒绝登录。
     */
    private boolean enabled = true;

    private String siteKey = "";

    private String secret = "";

    private String verifyUrl = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    public boolean ready() {
        return enabled && StringUtils.hasText(siteKey) && StringUtils.hasText(secret);
    }
}
