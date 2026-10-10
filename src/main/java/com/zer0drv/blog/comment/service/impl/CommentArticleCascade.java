package com.zer0drv.blog.comment.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zer0drv.blog.article.api.ArticleCascade;
import com.zer0drv.blog.comment.mapper.CommentMapper;
import com.zer0drv.blog.interaction.domain.CommentLike;
import com.zer0drv.blog.interaction.mapper.CommentLikeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * comment 模块的文章级联清场实现（issue #24 第三步）：文章彻底删除时，
 * 物理清理 comment（含已逻辑删除）+ comment_like。
 *
 * <p>comment_like 的清理随 comment 走：点赞依附于评论，评论消失点赞无存在意义
 * （与评论自身「彻底删除」路径 CommentServiceImpl 的口径一致）。
 *
 * @author Yoruhaki
 */
@Component
@RequiredArgsConstructor
public class CommentArticleCascade implements ArticleCascade {

    private final CommentMapper commentMapper;
    private final CommentLikeMapper commentLikeMapper;

    @Override
    public void purgeByArticleId(long articleId) {
        // 先清评论点赞（含已逻辑删除评论的点赞，手写 SQL 取全量评论 id），再清评论
        List<Long> commentIds = commentMapper.selectAllCommentIdsByArticleId(articleId);
        if (!commentIds.isEmpty()) {
            commentLikeMapper.delete(Wrappers.lambdaQuery(CommentLike.class)
                    .in(CommentLike::getCommentId, commentIds));
        }
        commentMapper.physicalDeleteCommentsByArticleId(articleId);
    }
}
