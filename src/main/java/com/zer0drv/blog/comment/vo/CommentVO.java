package com.zer0drv.blog.comment.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评论视图对象。主评论列表场景下内嵌前 3 条回复（replies，时间正序）+ replyCount；
 * 回复分页场景 replies 为 null。
 *
 * @author Yoruhaki
 */
@Data
public class CommentVO {

    private Long id;

    private Long articleId;

    private Long parentId;

    private String content;

    private Integer likeCount;

    /**
     * 回复总数（仅主评论有值）
     */
    private Long replyCount;

    private String status;

    private LocalDateTime createTime;

    /**
     * 评论人
     */
    private CommentUserVO user;

    /**
     * 被回复人（回复/@ 场景，主评论为 null）
     */
    private CommentUserVO replyToUser;

    /**
     * 前 3 条回复（仅主评论列表返回）
     */
    private List<CommentVO> replies;

    /**
     * 当前登录人是否已赞（匿名 false）
     */
    private Boolean liked;
}