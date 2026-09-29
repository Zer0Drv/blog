package com.zer0drv.blog.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 对象存储客户端。仅 blog.minio.enabled=true 时装配；关闭时上传走本地磁盘 fallback。
 *
 * @author Yoruhaki
 */
@Configuration
@ConditionalOnProperty(prefix = "blog.minio", name = "enabled", havingValue = "true")
public class MinioConfig {

    @Bean
    public MinioClient minioClient(@Value("${blog.minio.endpoint:http://localhost:9000}") String endpoint,
                                   @Value("${blog.minio.access-key:minioadmin}") String accessKey,
                                   @Value("${blog.minio.secret-key:minioadmin123}") String secretKey) {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
