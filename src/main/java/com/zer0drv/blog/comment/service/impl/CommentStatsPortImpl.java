package com.zer0drv.blog.comment.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zer0drv.blog.comment.api.CommentStatsEntry;
import com.zer0drv.blog.comment.api.CommentStatsPort;
import com.zer0drv.blog.comment.domain.Comment;
import com.zer0drv.blog.comment.enums.CommentStatus;
import com.zer0drv.blog.comment.mapper.CommentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@link CommentStatsPort} 实现（comment 内部包，issue #34 第五步）：
 * 统计 SQL 逐字节沿用自 AdminStatsServiceImpl 收口前的内联实现。
 *
 * @author Yoruhaki
 */
@Component
@RequiredArgsConstructor
public class CommentStatsPortImpl implements CommentStatsPort {

    private final CommentMapper commentMapper;

    @Override
    public long countNormal() {
        return commentMapper.selectCount(Wrappers.lambdaQuery(Comment.class)
                .eq(Comment::getStatus, CommentStatus.NORMAL.name()));
    }

    @Override
    public long countCreatedSince(LocalDateTime since) {
        return commentMapper.selectCount(Wrappers.lambdaQuery(Comment.class)
                .ge(Comment::getCreateTime, since));
    }

    @Override
    public List<CommentStatsEntry> listLatest(int limit) {
        return commentMapper.selectList(Wrappers.lambdaQuery(Comment.class)
                        .orderByDesc(Comment::getId)
                        .last("LIMIT " + limit))
                .stream()
                .map(comment -> new CommentStatsEntry(comment.getId(), comment.getArticleId(),
                        comment.getUserId(), comment.getContent(), comment.getCreateTime()))
                .toList();
    }
}
