package com.zer0drv.blog.interaction.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zer0drv.blog.article.domain.Article;
import com.zer0drv.blog.article.service.ArticleService;
import com.zer0drv.blog.article.vo.ArticleListVO;
import com.zer0drv.blog.comment.domain.Comment;
import com.zer0drv.blog.comment.mapper.CommentMapper;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.common.util.JwtSubjects;
import com.zer0drv.blog.interaction.domain.ArticleFavorite;
import com.zer0drv.blog.interaction.domain.ArticleLike;
import com.zer0drv.blog.interaction.domain.CommentLike;
import com.zer0drv.blog.interaction.mapper.ArticleFavoriteMapper;
import com.zer0drv.blog.interaction.mapper.ArticleLikeMapper;
import com.zer0drv.blog.interaction.mapper.CommentLikeMapper;
import com.zer0drv.blog.interaction.service.InteractionService;
import com.zer0drv.blog.social.enums.NotificationType;
import com.zer0drv.blog.social.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author Yoruhaki
 */
@Service
@RequiredArgsConstructor
public class InteractionServiceImpl implements InteractionService {

    private final ArticleLikeMapper articleLikeMapper;
    private final ArticleFavoriteMapper articleFavoriteMapper;
    private final CommentLikeMapper commentLikeMapper;
    private final CommentMapper commentMapper;
    private final ArticleService articleService;
    private final NotificationService notificationService;

    @Override
    public void likeArticle(Long articleId, Jwt jwt) {
        Article article = articleService.getById(articleId);
        if (Objects.isNull(article)) {
            throw new BusinessException(StatusCode.ARTICLE_NOT_EXIST);
        }
        Long userId = JwtSubjects.userIdOf(jwt);
        if (isLiked(articleId, userId)) {
            // 幂等：已赞则静默成功
            return;
        }
        boolean inserted = false;
        try {
            ArticleLike like = new ArticleLike();
            like.setArticleId(articleId);
            like.setUserId(userId);
            articleLikeMapper.insert(like);
            inserted = true;
        } catch (DuplicateKeyException _) {
            // 并发重复点赞：唯一索引兜底，静默成功（不再重复发通知）
        }
        if (inserted) {
            // M4 通知触发：首次点赞通知作者（防重：取消再赞不重复发；失败不影响主业务）
            notificationService.notify(article.getAuthorId(), NotificationType.ARTICLE_LIKE,
                    userId, articleId, null, article.getTitle(), true);
        }
    }

    @Override
    public void unlikeArticle(Long articleId, Jwt jwt) {
        Long userId = JwtSubjects.userIdOf(jwt);
        articleLikeMapper.delete(Wrappers.lambdaQuery(ArticleLike.class)
                .eq(ArticleLike::getArticleId, articleId)
                .eq(ArticleLike::getUserId, userId));
    }

    @Override
    public void favoriteArticle(Long articleId, Jwt jwt) {
        requireArticle(articleId);
        Long userId = JwtSubjects.userIdOf(jwt);
        Long count = articleFavoriteMapper.selectCount(Wrappers.lambdaQuery(ArticleFavorite.class)
                .eq(ArticleFavorite::getArticleId, articleId)
                .eq(ArticleFavorite::getUserId, userId));
        if (count > 0) {
            return;
        }
        try {
            ArticleFavorite favorite = new ArticleFavorite();
            favorite.setArticleId(articleId);
            favorite.setUserId(userId);
            articleFavoriteMapper.insert(favorite);
        } catch (DuplicateKeyException _) {
            // 并发重复收藏：唯一索引兜底，静默成功
        }
    }

    @Override
    public void unfavoriteArticle(Long articleId, Jwt jwt) {
        Long userId = JwtSubjects.userIdOf(jwt);
        articleFavoriteMapper.delete(Wrappers.lambdaQuery(ArticleFavorite.class)
                .eq(ArticleFavorite::getArticleId, articleId)
                .eq(ArticleFavorite::getUserId, userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void likeComment(Long commentId, Jwt jwt) {
        Comment comment = commentMapper.selectById(commentId);
        if (Objects.isNull(comment)) {
            throw new BusinessException(StatusCode.COMMENT_NOT_EXIST);
        }
        Long userId = JwtSubjects.userIdOf(jwt);
        Long count = commentLikeMapper.selectCount(Wrappers.lambdaQuery(CommentLike.class)
                .eq(CommentLike::getCommentId, commentId)
                .eq(CommentLike::getUserId, userId));
        if (count > 0) {
            return;
        }
        try {
            CommentLike like = new CommentLike();
            like.setCommentId(commentId);
            like.setUserId(userId);
            commentLikeMapper.insert(like);
        } catch (DuplicateKeyException _) {
            // 并发重复点赞：唯一索引兜底，静默成功（不再累加计数）
            return;
        }
        // 原子自增，防并发丢计数
        commentMapper.update(null, likeCountWrapper(commentId, true));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unlikeComment(Long commentId, Jwt jwt) {
        Long userId = JwtSubjects.userIdOf(jwt);
        int deleted = commentLikeMapper.delete(Wrappers.lambdaQuery(CommentLike.class)
                .eq(CommentLike::getCommentId, commentId)
                .eq(CommentLike::getUserId, userId));
        if (deleted > 0) {
            // 仅在确实取消了一条点赞时扣减计数（gt 0 兜底防负数）
            commentMapper.update(null, likeCountWrapper(commentId, false));
        }
    }

    @Override
    public PageResult<ArticleListVO> pageMyFavorites(long page, long size, Jwt jwt) {
        Long userId = JwtSubjects.userIdOf(jwt);
        Page<ArticleFavorite> result = articleFavoriteMapper.selectPage(new Page<>(page, size),
                Wrappers.lambdaQuery(ArticleFavorite.class)
                        .eq(ArticleFavorite::getUserId, userId)
                        .orderByDesc(ArticleFavorite::getId));
        List<Long> articleIds = result.getRecords().stream().map(ArticleFavorite::getArticleId).toList();
        if (articleIds.isEmpty()) {
            return PageResult.of(List.of(), result.getTotal(), page, size);
        }
        // 文章可能已被删除：内存过滤并保持收藏顺序
        Map<Long, Article> articleMap = articleService.listByIds(articleIds).stream()
                .collect(Collectors.toMap(Article::getId, Function.identity()));
        List<Article> articles = articleIds.stream()
                .map(articleMap::get)
                .filter(Objects::nonNull)
                .toList();
        return PageResult.of(articleService.assemble(articles), result.getTotal(), page, size);
    }

    /**
     * like_count 原子 +/-1 的 UpdateWrapper（一条 update 语句，不先查后写）
     */
    private LambdaUpdateWrapper<Comment> likeCountWrapper(Long commentId, boolean increment) {
        LambdaUpdateWrapper<Comment> wrapper = Wrappers.lambdaUpdate(Comment.class)
                .eq(Comment::getId, commentId);
        if (increment) {
            wrapper.setSql("like_count = like_count + 1");
        } else {
            wrapper.setSql("like_count = like_count - 1").gt(Comment::getLikeCount, 0);
        }
        return wrapper;
    }

    private void requireArticle(Long articleId) {
        if (Objects.isNull(articleService.getById(articleId))) {
            throw new BusinessException(StatusCode.ARTICLE_NOT_EXIST);
        }
    }

    private boolean isLiked(Long articleId, Long userId) {
        return articleLikeMapper.selectCount(Wrappers.lambdaQuery(ArticleLike.class)
                .eq(ArticleLike::getArticleId, articleId)
                .eq(ArticleLike::getUserId, userId)) > 0;
    }
}
