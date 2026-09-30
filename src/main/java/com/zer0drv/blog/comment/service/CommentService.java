package com.zer0drv.blog.comment.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.zer0drv.blog.article.domain.Article;
import com.zer0drv.blog.comment.domain.Comment;
import com.zer0drv.blog.comment.dto.CommentCreateDTO;
import com.zer0drv.blog.comment.vo.CommentVO;
import com.zer0drv.blog.common.response.PageResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * @author Yoruhaki
 */
public interface CommentService extends IService<Comment> {

    /**
     * 主评论分页（仅 status=NORMAL）。sort：time_desc(默认)/time_asc/hot。
     * 每条主评论内嵌前 3 条回复（时间正序）+ replyCount。jwt 可为 null（匿名）。
     */
    PageResult<CommentVO> pageRootComments(Long articleId, String sort, long page, long size, Jwt jwt);

    /**
     * 某主评论的全部回复分页（时间正序）。jwt 可为 null（匿名）。
     */
    PageResult<CommentVO> pageReplies(Long rootId, long page, long size, Jwt jwt);

    /**
     * 发表评论。parentId 为空 = 主评论；若 parentId 指向二级评论则归一化到其 root。
     * 文章须满足可见性谓词（PUBLISHED 且 publish_time 已到），返回评论id。
     * 命中敏感词 → FOLDED；审核开关开启 → PENDING（不通知）；否则 NORMAL + 通知。
     */
    Long create(CommentCreateDTO dto, Jwt jwt);

    /**
     * 评论创建后的通知分发（主评论通知文章作者；回复按 COMMENT_REPLY/MENTION 组合规则）。
     * 供 create() 与后台审核通过（approve）补发通知复用。
     */
    void notifyCommentCreated(Comment comment, Article article);

    /**
     * 删除评论（仅本人或 ADMIN，逻辑删除；删主评论连带逻辑删除其回复）
     */
    void delete(Long id, Jwt jwt);
}