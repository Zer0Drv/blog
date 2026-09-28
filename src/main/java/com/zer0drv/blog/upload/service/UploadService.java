package com.zer0drv.blog.upload.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * @author Yoruhaki
 */
public interface UploadService {

    /**
     * 上传图片（jpg/png/gif/webp，≤5MB），返回可访问的 URL 路径
     */
    String uploadImage(MultipartFile file);
}