package com.kean.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "kean.sms")
public class SmsProperties {

    /**
     * 是否在接口中回传验证码。生产必须保持 false。
     */
    private boolean exposeCode = false;

    /**
     * 为 true 时邮件未配置可把验证码打到控制台。生产必须 false。
     */
    private boolean allowConsole = false;
}
