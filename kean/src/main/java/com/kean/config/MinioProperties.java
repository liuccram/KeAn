package com.kean.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "kean.minio")
public class MinioProperties {

    /** 必填，通过 STORAGE_ENDPOINT 提供（S3 API 地址），不设默认值。 */
    private String endpoint;

    /** 必填，通过 STORAGE_ACCESS_KEY 提供，不设默认值。 */
    private String accessKey;

    /** 必填，通过 STORAGE_SECRET_KEY 提供，不设默认值。 */
    private String secretKey;

    private String bucket = "kean";
}
