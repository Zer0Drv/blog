package com.zer0drv.blog.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 对象存储客户端。仅 blog.minio.enabled=true 时装配；关闭时上传走本地磁盘 fallback。
 *
 * @author Yoruhaki
 */
@Slf4j
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

    /**
     * 启动期 bucket 自检：不存在则尝试创建（minio-init 容器失败时兜底）；
     * MinIO 不可用只告警不阻断启动，上传时才会报 FILE_UPLOAD_FAILED。
     */
    @Bean
    public ApplicationRunner minioBucketCheck(MinioClient minioClient,
                                              @Value("${blog.minio.bucket:blog-images}") String bucket) {
        return args -> {
            try {
                if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                    minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                    log.info("MinIO bucket '{}' 不存在，已自动创建（匿名下载策略依赖 minio-init 容器）", bucket);
                }
            } catch (Exception e) {
                log.error("MinIO 连接失败（{}），图片上传将不可用：{}", e.getClass().getSimpleName(), e.getMessage());
            }
        };
    }
}
