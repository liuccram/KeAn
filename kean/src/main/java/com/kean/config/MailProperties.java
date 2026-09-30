package com.kean.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@Getter
@Setter
@ConfigurationProperties(prefix = "kean.mail")
public class MailProperties {

    /**
     * 为 true 时走 QQ SMTP 真发信；未配置账号时自动回退到控制台打印。
     */
    private boolean enabled = false;

    private String from = "";

    private String fromName = "课安";

    public boolean ready() {
        return enabled && StringUtils.hasText(from);
    }
}
