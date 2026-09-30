package com.zer0drv.blog.attachment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 附件表
 *
 * @author Yoruhaki
 */
@Data
@TableName(value = "attachment")
public class Attachment {

    /**
     * 存储类型：MinIO 对象存储
     */
    public static final String STORAGE_MINIO = "MINIO";

    /**
     * 存储类型：本地磁盘 fallback
     */
    public static final String STORAGE_LOCAL = "LOCAL";

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 上传者id
     */
    @TableField(value = "user_id")
    private Long userId;

    /**
     * 分组id（NULL=未分组）
     */
    @TableField(value = "group_id")
    private Long groupId;

    /**
     * 访问URL
     */
    @TableField(value = "url")
    private String url;

    /**
     * 存储对象名（yyyyMM/uuid.ext）
     */
    @TableField(value = "object_key")
    private String objectKey;

    /**
     * 存储：MINIO/LOCAL
     */
    @TableField(value = "storage")
    private String storage;

    /**
     * 原始文件名
     */
    @TableField(value = "filename")
    private String filename;

    /**
     * 媒体类型
     */
    @TableField(value = "media_type")
    private String mediaType;

    /**
     * 大小（字节）
     */
    @TableField(value = "size_bytes")
    private Long sizeBytes;

    /**
     * 创建时间
     */
    @TableField(value = "create_time")
    private LocalDateTime createTime;

    /**
     * 逻辑删除：0-未删除；1-删除
     * （本工程 MP 全局 logic-delete-field 配置不生效，须实体级 @TableLogic，与 Article/Comment 一致）
     */
    @com.baomidou.mybatisplus.annotation.TableLogic
    @TableField(value = "deleted")
    private Short deleted;
}
