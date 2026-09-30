package com.zer0drv.blog.attachment.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 附件 VO（用户侧，不含 userId——只能看到自己的数据）
 *
 * @author Yoruhaki
 */
@Data
public class AttachmentVO {

    /**
     * 附件id
     */
    private Long id;

    /**
     * 访问URL
     */
    private String url;

    /**
     * 存储对象名（yyyyMM/uuid.ext）
     */
    private String objectKey;

    /**
     * 存储：MINIO/LOCAL
     */
    private String storage;

    /**
     * 原始文件名
     */
    private String filename;

    /**
     * 媒体类型
     */
    private String mediaType;

    /**
     * 大小（字节）
     */
    private Long sizeBytes;

    /**
     * 分组id（null=未分组）
     */
    private Long groupId;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
