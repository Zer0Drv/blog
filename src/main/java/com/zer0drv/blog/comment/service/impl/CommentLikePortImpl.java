package com.zer0drv.blog.comment.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zer0drv.blog.comment.api.CommentLikePort;
import com.zer0drv.blog.comment.api.CommentRef;
import com.zer0drv.blog.comment.domain.Comment;
import com.zer0drv.blog.comment.domain.CommentLike;
import com.zer0drv.blog.comment.enums.CommentStatus;
import com.zer0drv.blog.comment.mapper.CommentLikeMapper;
import com.zer0drv.blog.comment.mapper.CommentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * {@link CommentLikePort} 的 comment 模块实现（issue #34 第一步）：
 * comment_like 关联与 comment.like_count 计数在本模块内维护，
 * 语义逐字节沿用自 interaction 模块收口前的实现。
 *
 * @author Yoruhaki
 */
@Component
@RequiredArgsConstructor
public class CommentLikePortImpl implements CommentLikePort {

    private final CommentLikeMapper commentLikeMapper;
    private final CommentMapper commentMapper;

    @Override
    public Optional<CommentRef> findLikeable(long commentId) {
        Comment comment = commentMapper.selectById(commentId);
        // 仅 NORMAL 评论可点赞：FOLDED/PENDING 评论对外不可见，视为「不存在」
        if (Objects.isNull(comment) || !CommentStatus.NORMAL.name().equals(comment.getStatus())) {
            return Optional.empty();
        }
        return Optional.of(new CommentRef(
                comment.getId(), comment.getUserId(), comment.getArticleId(), comment.getContent()));
    }

    @Override
    public boolean addLike(long commentId, long userId) {
        Long count = commentLikeMapper.selectCount(Wrappers.lambdaQuery(CommentLike.class)
                .eq(CommentLike::getCommentId, commentId)
                .eq(CommentLike::getUserId, userId));
        if (count > 0) {
            // 幂等：已赞则静默成功
            return false;
        }
        try {
            CommentLike like = new CommentLike();
            like.setCommentId(commentId);
            like.setUserId(userId);
            commentLikeMapper.insert(like);
        } catch (DuplicateKeyException _) {
            // 并发重复点赞：唯一索引兜底，静默成功（不再累加计数）
            return false;
        }
        // 原子自增，防并发丢计数
        commentMapper.update(null, likeCountWrapper(commentId, true));
        return true;
    }

    @Override
    public void removeLike(long commentId, long userId) {
        int deleted = commentLikeMapper.delete(Wrappers.lambdaQuery(CommentLike.class)
                .eq(CommentLike::getCommentId, commentId)
                .eq(CommentLike::getUserId, userId));
        if (deleted > 0) {
            // 仅在确实取消了一条点赞时扣减计数（gt 0 兜底防负数）
            commentMapper.update(null, likeCountWrapper(commentId, false));
        }
    }

    /**
     * like_count 原子 +/-1 的 UpdateWrapper（一条 update 语句，不先查后写）
     */
    private LambdaUpdateWrapper<Comment> likeCountWrapper(long commentId, boolean increment) {
        LambdaUpdateWrapper<Comment> wrapper = Wrappers.lambdaUpdate(Comment.class)
                .eq(Comment::getId, commentId);
        if (increment) {
            wrapper.setSql("like_count = like_count + 1");
        } else {
            wrapper.setSql("like_count = like_count - 1").gt(Comment::getLikeCount, 0);
        }
        return wrapper;
    }
}
