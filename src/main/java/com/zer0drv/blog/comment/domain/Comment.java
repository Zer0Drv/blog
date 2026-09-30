package com.zer0drv.blog.comment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zer0drv.blog.comment.vo.CommentVO;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评论表（严格两层楼中楼：parent_id=0 为主评论，回复的 parent_id 一律为主评论id）
 *
 * @author Yoruhaki
 */
@Data
@TableName(value = "comment")
@AutoMapper(target = CommentVO.class, reverseConvertGenerate = false)
public class Comment {

    /**
     * 评论id
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 文章id
     */
    @TableField(value = "article_id")
    private Long articleId;

    /**
     * 评论人id
     */
    @TableField(value = "user_id")
    private Long userId;

    /**
     * 0=主评论；否则为主评论id
     */
    @TableField(value = "parent_id")
    private Long parentId;

    /**
     * 被回复人id（回复/@ 场景）
     */
    @TableField(value = "reply_to_user_id")
    private Long replyToUserId;

    /**
     * 评论内容
     */
    @TableField(value = "content")
    private String content;

    /**
     * 点赞数
     */
    @TableField(value = "like_count")
    private Integer likeCount;

    /**
     * 状态：NORMAL / FOLDED
     */
    @TableField(value = "status")
    private String status;

    /**
     * 创建时间
     */
    @TableField(value = "create_time")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField(value = "update_time")
    private LocalDateTime updateTime;

    /**
     * 逻辑删除：0-未删除；1-删除
     * （显式 @TableLogic：IT 的 application.yaml 整体遮蔽主配置、不含全局 logic-delete-field，
     * 注解保证两环境行为一致；P0 评论回收站依赖逻辑删除）
     */
    @TableLogic
    @TableField(value = "deleted")
    private Short deleted;
}