package com.zer0drv.blog.interaction.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zer0drv.blog.article.api.ArticleCatalog;
import com.zer0drv.blog.article.api.ArticleRef;
import com.zer0drv.blog.article.vo.ArticleListVO;
import com.zer0drv.blog.comment.domain.Comment;
import com.zer0drv.blog.comment.enums.CommentStatus;
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
import java.util.Objects;

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
    private final ArticleCatalog articleCatalog;
    private final NotificationService notificationService;

    @Override
    public void likeArticle(Long articleId, Jwt jwt) {
        // 互动可见性断言：不可见文章对外一律表现为「不存在」，不可点赞，也避免泄露未发布内容
        ArticleRef article = articleCatalog.requireVisible(articleId);
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
            notificationService.notify(article.authorId(), NotificationType.ARTICLE_LIKE,
                    userId, articleId, null, article.title(), true);
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
        articleCatalog.requireVisible(articleId);
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
        // 仅 NORMAL 评论可点赞：FOLDED/PENDING 评论对外不可见，视为「不存在」
        if (Objects.isNull(comment) || !CommentStatus.NORMAL.name().equals(comment.getStatus())) {
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
        // 通知评论作者（首次点赞才触发；自己给自己不发、失败不回滚主业务均由 notify 兜底）
        String summary = Objects.nonNull(comment.getContent()) && comment.getContent().length() > 50
                ? comment.getContent().substring(0, 50) : comment.getContent();
        notificationService.notify(comment.getUserId(), NotificationType.COMMENT_LIKE, userId,
                comment.getArticleId(), commentId, summary, true);
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
        // 收藏夹语义：已删除的文章被过滤，已下架 / 回草稿的仍保留；保持收藏顺序
        return PageResult.of(articleCatalog.listByIdsPreserveOrder(articleIds), result.getTotal(), page, size);
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

    private boolean isLiked(Long articleId, Long userId) {
        return articleLikeMapper.selectCount(Wrappers.lambdaQuery(ArticleLike.class)
                .eq(ArticleLike::getArticleId, articleId)
                .eq(ArticleLike::getUserId, userId)) > 0;
    }
}
