package com.zer0drv.blog.upload.service.impl;

import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.upload.service.UploadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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
        String month = LocalDate.now().format(MONTH_FORMATTER);
        String filename = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        Path dir = Paths.get(uploadDir).toAbsolutePath().normalize().resolve(month);
        try {
            Files.createDirectories(dir);
            file.transferTo(dir.resolve(filename));
        } catch (IOException e) {
            log.error("文件写入失败", e);
            throw new BusinessException(StatusCode.FILE_UPLOAD_FAILED);
        }
        return "/uploads/" + month + "/" + filename;
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