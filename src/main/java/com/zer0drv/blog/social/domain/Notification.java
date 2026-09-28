package com.zer0drv.blog.social.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知表（deleted 走 MyBatis-Plus 全局逻辑删除）
 *
 * @author Yoruhaki
 */
@Data
@TableName(value = "notification")
public class Notification {

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 接收者id
     */
    @TableField(value = "user_id")
    private Long userId;

    /**
     * 类型：COMMENT_REPLY / MENTION / ARTICLE_LIKE / FOLLOW / PRIVATE_MESSAGE / SYSTEM
     */
    @TableField(value = "type")
    private String type;

    /**
     * 触发人id（系统通知为空）
     */
    @TableField(value = "actor_id")
    private Long actorId;

    /**
     * 关联文章id
     */
    @TableField(value = "article_id")
    private Long articleId;

    /**
     * 关联评论id
     */
    @TableField(value = "comment_id")
    private Long commentId;

    /**
     * 摘要（如评论片段/文章标题）
     */
    @TableField(value = "summary")
    private String summary;

    /**
     * 已读：0-未读；1-已读
     */
    @TableField(value = "read_flag")
    private Short readFlag;

    /**
     * 创建时间
     */
    @TableField(value = "create_time")
    private LocalDateTime createTime;

    /**
     * 逻辑删除：0-未删除；1-删除
     */
    @TableField(value = "deleted")
    private Short deleted;
}
