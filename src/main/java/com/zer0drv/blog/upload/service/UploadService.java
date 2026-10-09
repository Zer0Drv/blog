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

    /**
     * 物理删除存储对象（附件删除时调用）：MinIO removeObject / 本地磁盘删文件，
     * 存储后端的判定与上传时一致（blog.minio.enabled + MinioClient 是否可用）。
     * best-effort：失败（MinIO 不可用、文件已不存在、对象名非法）只告警不抛异常——
     * 存储清理不得阻塞用户可见的删除。
     */
    void deleteObject(String objectKey);
}