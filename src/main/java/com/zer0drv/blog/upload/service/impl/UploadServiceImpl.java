package com.zer0drv.blog.upload.service.impl;

import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.upload.service.UploadService;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * @author Yoruhaki
 */
@Slf4j
@Service
public class UploadServiceImpl implements UploadService {

    /**
     * 单文件大小上限：5MB（另有 spring.servlet.multipart 配置兜底）
     */
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;

    /**
     * 允许的图片扩展名
     */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp");

    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");

    /**
     * 上传根目录，可用环境变量 BLOG_UPLOAD_DIR 覆盖，默认 ./uploads
     */
    @Value("${blog.upload-dir:./uploads}")
    private String uploadDir;

    /**
     * MinIO 开关与桶配置（enabled=false 时 MinioClient Bean 不存在，走本地磁盘 fallback）
     */
    @Value("${blog.minio.enabled:false}")
    private boolean minioEnabled;

    @Value("${blog.minio.bucket:blog-images}")
    private String minioBucket;

    /**
     * 拼返回 URL 用的浏览器可达地址，默认同 endpoint
     */
    @Value("${blog.minio.public-url:http://localhost:9000}")
    private String minioPublicUrl;

    private final ObjectProvider<MinioClient> minioClientProvider;

    public UploadServiceImpl(ObjectProvider<MinioClient> minioClientProvider) {
        this.minioClientProvider = minioClientProvider;
    }

    @Override
    public String uploadImage(MultipartFile file) {
        if (Objects.isNull(file) || file.isEmpty()) {
            throw new BusinessException(StatusCode.PARAM_INVALID.getCode(), "上传文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(StatusCode.FILE_TOO_LARGE);
        }
        String contentType = file.getContentType();
        if (Objects.nonNull(contentType) && !contentType.startsWith("image/")) {
            throw new BusinessException(StatusCode.FILE_TYPE_NOT_ALLOWED);
        }
        String extension = resolveExtension(file.getOriginalFilename());
        String objectName = buildObjectName(extension);
        MinioClient minioClient = minioEnabled ? minioClientProvider.getIfAvailable() : null;
        if (Objects.nonNull(minioClient)) {
            return uploadToMinio(minioClient, file, objectName, contentType);
        }
        return uploadToLocal(file, objectName);
    }

    /**
     * 对象名/文件名：{yyyyMM}/{uuid}.{ext}
     */
    private String buildObjectName(String extension) {
        String month = LocalDate.now().format(MONTH_FORMATTER);
        String filename = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        return month + "/" + filename;
    }

    /**
     * MinIO 存储：putObject 后返回绝对 URL {publicUrl}/{bucket}/{objectName}
     */
    private String uploadToMinio(MinioClient minioClient, MultipartFile file, String objectName, String contentType) {
        try (InputStream in = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioBucket)
                    .object(objectName)
                    .stream(in, file.getSize(), -1)
                    .contentType(Objects.nonNull(contentType) ? contentType : "application/octet-stream")
                    .build());
        } catch (Exception e) {
            log.error("MinIO 上传失败", e);
            throw new BusinessException(StatusCode.FILE_UPLOAD_FAILED);
        }
        String base = minioPublicUrl.endsWith("/")
                ? minioPublicUrl.substring(0, minioPublicUrl.length() - 1) : minioPublicUrl;
        return base + "/" + minioBucket + "/" + objectName;
    }

    /**
     * 本地磁盘存储（fallback）：返回 /uploads/** 相对 URL，由 WebMvcConfig 映射
     */
    private String uploadToLocal(MultipartFile file, String objectName) {
        Path dir = Paths.get(uploadDir).toAbsolutePath().normalize()
                .resolve(objectName.substring(0, objectName.indexOf('/')));
        String filename = objectName.substring(objectName.indexOf('/') + 1);
        try {
            Files.createDirectories(dir);
            file.transferTo(dir.resolve(filename));
        } catch (IOException e) {
            log.error("文件写入失败", e);
            throw new BusinessException(StatusCode.FILE_UPLOAD_FAILED);
        }
        return "/uploads/" + objectName;
    }

    /**
     * 取原始文件名扩展名（小写），非法或不在白名单内直接拒绝
     */
    private String resolveExtension(String originalFilename) {
        if (Objects.isNull(originalFilename)) {
            throw new BusinessException(StatusCode.FILE_TYPE_NOT_ALLOWED);
        }
        int dotIndex = originalFilename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == originalFilename.length() - 1) {
            throw new BusinessException(StatusCode.FILE_TYPE_NOT_ALLOWED);
        }
        String extension = originalFilename.substring(dotIndex + 1).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(StatusCode.FILE_TYPE_NOT_ALLOWED);
        }
        return extension;
    }
}