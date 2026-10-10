package com.zer0drv.blog.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zer0drv.blog.admin.service.AdminCommentService;
import com.zer0drv.blog.admin.vo.AdminCommentVO;
import com.zer0drv.blog.article.api.ArticleCatalog;
import com.zer0drv.blog.article.api.ArticleRef;
import com.zer0drv.blog.comment.domain.Comment;
import com.zer0drv.blog.comment.enums.CommentStatus;
import com.zer0drv.blog.comment.mapper.CommentMapper;
import com.zer0drv.blog.comment.service.CommentService;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.interaction.domain.CommentLike;
import com.zer0drv.blog.interaction.mapper.CommentLikeMapper;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author Yoruhaki
 */
@Service
@RequiredArgsConstructor
public class AdminCommentServiceImpl implements AdminCommentService {

    /**
     * 列表 status 伪状态：回收站（deleted=1 的评论，P0 §2.4）
     */
    private static final String STATUS_TRASH = "TRASH";

    private final CommentMapper commentMapper;
    private final ArticleCatalog articleCatalog;
    private final UserService userService;
    private final CommentService commentService;
    private final CommentLikeMapper commentLikeMapper;

    @Override
    public PageResult<AdminCommentVO> pageComments(long page, long size, String status, String keyword, Long articleId) {
        // 伪状态 TRASH：回收站走自定义 SQL（MP 全局逻辑删除会自动拼 deleted=0，Wrapper 绕不过）
        if (STATUS_TRASH.equals(status)) {
            Page<Comment> trash = commentMapper.pageTrash(new Page<>(page, size), keyword, articleId);
            return PageResult.of(assemble(trash.getRecords()), trash.getTotal(), page, size);
        }
        if (Objects.nonNull(status) && !status.isBlank() && !CommentStatus.isValid(status)) {
            throw new BusinessException(StatusCode.PARAM_INVALID);
        }
        LambdaQueryWrapper<Comment> wrapper = Wrappers.lambdaQuery(Comment.class)
                .eq(Objects.nonNull(status) && !status.isBlank(), Comment::getStatus, status)
                .eq(Objects.nonNull(articleId), Comment::getArticleId, articleId)
                .like(Objects.nonNull(keyword) && !keyword.isBlank(), Comment::getContent, keyword)
                .orderByDesc(Comment::getId);
        Page<Comment> result = commentMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(assemble(result.getRecords()), result.getTotal(), page, size);
    }

    @Override
    public void fold(Long id) {
        requireComment(id);
        commentMapper.update(null, Wrappers.lambdaUpdate(Comment.class)
                .set(Comment::getStatus, CommentStatus.FOLDED.name())
                .eq(Comment::getId, id));
    }

    @Override
    public void unfold(Long id) {
        requireComment(id);
        commentMapper.update(null, Wrappers.lambdaUpdate(Comment.class)
                .set(Comment::getStatus, CommentStatus.NORMAL.name())
                .eq(Comment::getId, id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Comment comment = requireComment(id);
        commentMapper.deleteById(id);
        // 删主评论连带逻辑删除其回复
        if (comment.getParentId() == 0L) {
            commentMapper.delete(Wrappers.lambdaQuery(Comment.class).eq(Comment::getParentId, id));
        }
    }

    @Override
    public void approve(Long id) {
        Comment comment = requireComment(id);
        requirePending(comment);
        commentMapper.update(null, Wrappers.lambdaUpdate(Comment.class)
                .set(Comment::getStatus, CommentStatus.NORMAL.name())
                .eq(Comment::getId, id));
        // P0 §2.1：PENDING 期间未发通知，审核通过补发（走 create 同一 notify 路径，自然带邮件；
        // notify 内部自兜底，失败不影响审核结果）。文章已被删（查不到）时跳过通知
        Optional<ArticleRef> article = articleCatalog.findRef(comment.getArticleId());
        if (article.isPresent()) {
            comment.setStatus(CommentStatus.NORMAL.name());
            commentService.notifyCommentCreated(comment, article.get());
        }
    }

    @Override
    public void reject(Long id) {
        Comment comment = requireComment(id);
        requirePending(comment);
        // 审核拒绝 → FOLDED（不通知）
        commentMapper.update(null, Wrappers.lambdaUpdate(Comment.class)
                .set(Comment::getStatus, CommentStatus.FOLDED.name())
                .eq(Comment::getId, id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restore(Long id) {
        Comment comment = commentMapper.selectDeletedById(id);
        if (Objects.isNull(comment)) {
            throw new BusinessException(StatusCode.COMMENT_NOT_EXIST);
        }
        commentMapper.restoreById(id);
        if (comment.getParentId() == 0L) {
            // 已知近似：无法区分回复是随楼主删还是单独删，主评论恢复时连同 deleted=1 的回复一并恢复
            commentMapper.restoreRepliesByRootId(id);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void forceDelete(Long id) {
        Comment comment = commentMapper.selectAnyById(id);
        if (Objects.isNull(comment)) {
            throw new BusinessException(StatusCode.COMMENT_NOT_EXIST);
        }
        List<Long> likeTargetIds = new ArrayList<>();
        likeTargetIds.add(id);
        if (comment.getParentId() == 0L) {
            // 主评论：连带物理删全部回复（含回收站中的）与这些回复的点赞关联
            likeTargetIds.addAll(commentMapper.selectReplyIdsByRootId(id));
            commentMapper.physicalDeleteRepliesByRootId(id);
        }
        commentMapper.physicalDeleteById(id);
        // comment_like 实体不映射 deleted 列，Wrapper 删除即物理删除
        commentLikeMapper.delete(Wrappers.lambdaQuery(CommentLike.class)
                .in(CommentLike::getCommentId, likeTargetIds));
    }

    /**
     * 审核操作前置：仅 PENDING 可执行
     */
    private void requirePending(Comment comment) {
        if (!CommentStatus.PENDING.name().equals(comment.getStatus())) {
            throw new BusinessException(StatusCode.PARAM_INVALID.getCode(), "仅待审核评论可执行该操作");
        }
    }

    private Comment requireComment(Long id) {
        Comment comment = commentMapper.selectById(id);
        if (Objects.isNull(comment)) {
            throw new BusinessException(StatusCode.COMMENT_NOT_EXIST);
        }
        return comment;
    }

    /**
     * 批量组装管理评论 VO：username / articleTitle 批量联查，避免 N+1
     */
    private List<AdminCommentVO> assemble(List<Comment> comments) {
        if (comments.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = comments.stream().map(Comment::getUserId).collect(Collectors.toSet());
        Map<Long, User> userMap = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Set<Long> articleIds = comments.stream().map(Comment::getArticleId).collect(Collectors.toSet());
        // 管理端评论列表标题联查：任意状态、未删除（findRef 语义的批量形态），不按可见性过滤
        Map<Long, String> articleTitleMap = articleCatalog.listRefsByIds(articleIds).stream()
                .collect(Collectors.toMap(ArticleRef::id, ArticleRef::title));
        return comments.stream().map(comment -> {
            AdminCommentVO vo = new AdminCommentVO();
            vo.setId(comment.getId());
            vo.setArticleId(comment.getArticleId());
            vo.setParentId(comment.getParentId());
            vo.setContent(comment.getContent());
            vo.setLikeCount(comment.getLikeCount());
            vo.setStatus(comment.getStatus());
            vo.setCreateTime(comment.getCreateTime());
            User user = userMap.get(comment.getUserId());
            vo.setUsername(Objects.nonNull(user) ? user.getUsername() : null);
            vo.setArticleTitle(articleTitleMap.get(comment.getArticleId()));
            return vo;
        }).collect(Collectors.toList());
    }
}
