package com.zer0drv.blog.comment.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 发表评论入参。parentId 为空 = 主评论；回复时 parentId 指主评论
 * （若传入二级评论 id，服务端归一化到其 root）。content 在服务端统一校验
 * （非空且 ≤1000，抛 COMMENT_CONTENT_INVALID），故此处不加 Bean Validation。
 *
 * @author Yoruhaki
 */
@Data
public class CommentCreateDTO {

    /**
     * 文章id
     */
    @NotNull(message = "文章id不能为空")
    private Long articleId;

    /**
     * 评论内容（≤1000，服务端校验）
     */
    private String content;

    /**
     * 主评论id（空/0 = 主评论）
     */
    private Long parentId;

    /**
     * 被回复人id（回复/@ 场景，可空）
     */
    private Long replyToUserId;
}