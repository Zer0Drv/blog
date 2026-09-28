package com.zer0drv.blog.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zer0drv.blog.admin.service.AdminCommentService;
import com.zer0drv.blog.admin.vo.AdminCommentVO;
import com.zer0drv.blog.article.domain.Article;
import com.zer0drv.blog.article.mapper.ArticleMapper;
import com.zer0drv.blog.comment.domain.Comment;
import com.zer0drv.blog.comment.enums.CommentStatus;
import com.zer0drv.blog.comment.mapper.CommentMapper;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author Yoruhaki
 */
@Service
@RequiredArgsConstructor
public class AdminCommentServiceImpl implements AdminCommentService {

    private final CommentMapper commentMapper;
    private final ArticleMapper articleMapper;
    private final UserService userService;

    @Override
    public PageResult<AdminCommentVO> pageComments(long page, long size, String status, String keyword, Long articleId) {
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
        Map<Long, String> articleTitleMap = articleMapper.selectByIds(articleIds).stream()
                .collect(Collectors.toMap(Article::getId, Article::getTitle));
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
