package com.kean.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "kean.minio")
public class MinioProperties {

    /** 必填，通过 MINIO_ENDPOINT 提供，不设默认值。 */
    private String endpoint;

    /** 必填，通过 MINIO_ACCESS_KEY 提供，不设默认值。 */
    private String accessKey;

    /** 必填，通过 MINIO_SECRET_KEY 提供，不设默认值。 */
    private String secretKey;

    private String bucket = "kean";
}
