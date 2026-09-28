package com.zer0drv.blog.admin.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理后台评论列表项：在 M3 评论字段基础上冗余 username / articleTitle，
 * 便于治理列表直接展示，无需前端二次查询。
 *
 * @author Yoruhaki
 */
@Data
public class AdminCommentVO {

    private Long id;

    private Long articleId;

    private Long parentId;

    private String content;

    private Integer likeCount;

    private String status;

    private LocalDateTime createTime;

    /**
     * 评论人用户名（冗余）
     */
    private String username;

    /**
     * 所属文章标题（冗余；文章已删除时为 null）
     */
    private String articleTitle;
}
