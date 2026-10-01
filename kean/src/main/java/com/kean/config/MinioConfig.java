package com.kean.config;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 对象存储客户端装配。
 *
 * <p>客户端库仍然是 minio-java —— 它是纯 S3 客户端（SigV4 签名 + path-style 寻址），
 * 与具体服务端实现无关；服务端已从 MinIO 换成 RustFS，见 docs/ops/rustfs.md。
 * 类名保留 Minio* 是因为它标注的就是这个客户端库，对外只体现为 kean.minio.* 这几个配置项。
 *
 * <p>配置三项必填，缺失时启动期直接抛异常（fail-fast），
 * 避免历史上"配置错误表现为上传静默失败"的问题。
 */
@Configuration
public class MinioConfig {

    private static final Logger log = LoggerFactory.getLogger(MinioConfig.class);

    @Bean
    public MinioClient minioClient(MinioProperties properties) {
        if (!StringUtils.hasText(properties.getEndpoint())
                || !StringUtils.hasText(properties.getAccessKey())
                || !StringUtils.hasText(properties.getSecretKey())) {
            throw new IllegalStateException(
                    "对象存储未配置完整：请设置 STORAGE_ENDPOINT / STORAGE_ACCESS_KEY / STORAGE_SECRET_KEY");
        }
        MinioClient client = MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(properties.getBucket()).build());
            if (exists) {
                log.info("对象存储已连接 {} / {}", properties.getEndpoint(), properties.getBucket());
            } else {
                log.error("对象存储桶 {} 不存在，请确认已创建（本地见 docker-compose.dev.yml 的 rustfs-init）",
                        properties.getBucket());
            }
        } catch (Exception ex) {
            log.warn("对象存储连通性探测失败：{} / {}，{}。客户端已创建，上传时若仍失败请检查网络与凭据。",
                    properties.getEndpoint(), properties.getBucket(), ex.getMessage());
        }
        return client;
    }
}
