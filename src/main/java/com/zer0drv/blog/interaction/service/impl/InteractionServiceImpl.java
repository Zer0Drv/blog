package com.zer0drv.blog.interaction.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zer0drv.blog.article.api.ArticleCatalog;
import com.zer0drv.blog.article.api.ArticleRef;
import com.zer0drv.blog.article.vo.ArticleListVO;
import com.zer0drv.blog.comment.api.CommentLikePort;
import com.zer0drv.blog.comment.api.CommentRef;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.common.util.JwtSubjects;
import com.zer0drv.blog.interaction.domain.ArticleFavorite;
import com.zer0drv.blog.interaction.domain.ArticleLike;
import com.zer0drv.blog.interaction.mapper.ArticleFavoriteMapper;
import com.zer0drv.blog.interaction.mapper.ArticleLikeMapper;
import com.zer0drv.blog.interaction.service.InteractionService;
import com.zer0drv.blog.social.service.NotificationIntent;
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
    private final CommentLikePort commentLikePort;
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
            notificationService.notify(NotificationIntent.articleLike(
                    article.authorId(), userId, articleId, article.title()));
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
        // 评论数据经 comment.api 端口访问：不存在 / FOLDED / PENDING 一律表现为「不存在」
        CommentRef comment = commentLikePort.findLikeable(commentId)
                .orElseThrow(() -> new BusinessException(StatusCode.COMMENT_NOT_EXIST));
        Long userId = JwtSubjects.userIdOf(jwt);
        if (!commentLikePort.addLike(commentId, userId)) {
            // 幂等（含并发重复点赞）：静默成功，不重复发通知
            return;
        }
        // 通知评论作者（首次点赞才触发；自己给自己不发、失败不回滚主业务均由 notify 兜底）
        String summary = Objects.nonNull(comment.content()) && comment.content().length() > 50
                ? comment.content().substring(0, 50) : comment.content();
        notificationService.notify(NotificationIntent.commentLike(
                comment.authorId(), userId, comment.articleId(), commentId, summary));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unlikeComment(Long commentId, Jwt jwt) {
        Long userId = JwtSubjects.userIdOf(jwt);
        // 确实取消了一条点赞才扣减计数，语义在端口实现内
        commentLikePort.removeLike(commentId, userId);
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

    private boolean isLiked(Long articleId, Long userId) {
        return articleLikeMapper.selectCount(Wrappers.lambdaQuery(ArticleLike.class)
                .eq(ArticleLike::getArticleId, articleId)
                .eq(ArticleLike::getUserId, userId)) > 0;
    }
}
