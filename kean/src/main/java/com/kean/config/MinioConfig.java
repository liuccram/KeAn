package com.kean.config;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class MinioConfig {

    private static final Logger log = LoggerFactory.getLogger(MinioConfig.class);

    @Bean
    public MinioClient minioClient(MinioProperties properties) {
        if (!StringUtils.hasText(properties.getEndpoint())
                || !StringUtils.hasText(properties.getAccessKey())
                || !StringUtils.hasText(properties.getSecretKey())) {
            throw new IllegalStateException(
                    "MinIO 未配置完整：请设置 MINIO_ENDPOINT / MINIO_ACCESS_KEY / MINIO_SECRET_KEY");
        }
        MinioClient client = MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(properties.getBucket()).build());
            if (exists) {
                log.info("MinIO 已连接 {} / {}", properties.getEndpoint(), properties.getBucket());
            } else {
                log.error("MinIO 桶 {} 不存在，请确认已创建", properties.getBucket());
            }
        } catch (Exception ex) {
            log.warn("MinIO 连通性探测失败：{} / {}，{}。客户端已创建，上传时若仍失败请检查网络与凭据。",
                    properties.getEndpoint(), properties.getBucket(), ex.getMessage());
        }
        return client;
    }
}
