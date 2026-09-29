package com.zer0drv.blog.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketPolicyArgs;
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
     * 启动期幂等自检：bucket 不存在则创建，并无条件（重）设匿名只读（download）策略，
     * 保证图片直链可匿名访问（原 minio-init 容器的职责已下沉到此处，不再依赖额外容器）；
     * MinIO 不可用只告警不阻断启动，上传时才会报 FILE_UPLOAD_FAILED。
     */
    @Bean
    public ApplicationRunner minioBucketCheck(MinioClient minioClient,
                                              @Value("${blog.minio.bucket:blog-images}") String bucket) {
        return args -> {
            try {
                if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                    minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                    log.info("MinIO bucket '{}' 不存在，已自动创建", bucket);
                }
                // 匿名只读策略：允许任何人 GetObject（图片直链由该策略提供，由本自检幂等保证）
                String policyJson = """
                        {
                          "Version": "2012-10-17",
                          "Statement": [
                            {
                              "Effect": "Allow",
                              "Principal": { "AWS": [ "*" ] },
                              "Action": [ "s3:GetObject" ],
                              "Resource": [ "arn:aws:s3:::%s/*" ]
                            }
                          ]
                        }
                        """.formatted(bucket);
                minioClient.setBucketPolicy(
                        SetBucketPolicyArgs.builder().bucket(bucket).config(policyJson).build());
                log.info("MinIO bucket '{}' 匿名下载策略已由本自检设置（允许匿名 GetObject）", bucket);
            } catch (Exception e) {
                log.error("MinIO 连接失败（{}），图片上传将不可用：{}", e.getClass().getSimpleName(), e.getMessage());
            }
        };
    }
}
