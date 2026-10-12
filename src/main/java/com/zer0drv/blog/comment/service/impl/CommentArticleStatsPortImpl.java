package com.zer0drv.blog.comment.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zer0drv.blog.article.api.ArticleCommentStatsPort;
import com.zer0drv.blog.comment.domain.Comment;
import com.zer0drv.blog.comment.enums.CommentStatus;
import com.zer0drv.blog.comment.mapper.CommentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link ArticleCommentStatsPort} 的 comment 模块实现（issue #34 第三步）：
 * 文章可见评论计数在本模块内查询，口径逐字节沿用自文章域 fillInteraction
 * 收口前的实现（仅 status=NORMAL）。
 *
 * @author Yoruhaki
 */
@Component
@RequiredArgsConstructor
public class CommentArticleStatsPortImpl implements ArticleCommentStatsPort {

    private final CommentMapper commentMapper;

    @Override
    public long countVisibleByArticleId(long articleId) {
        return commentMapper.selectCount(Wrappers.lambdaQuery(Comment.class)
                .eq(Comment::getArticleId, articleId)
                .eq(Comment::getStatus, CommentStatus.NORMAL.name()));
    }
}
