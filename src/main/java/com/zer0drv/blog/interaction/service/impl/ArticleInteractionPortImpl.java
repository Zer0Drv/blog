package com.zer0drv.blog.interaction.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zer0drv.blog.article.api.ArticleInteractionPort;
import com.zer0drv.blog.article.api.ArticleInteractionStats;
import com.zer0drv.blog.interaction.domain.ArticleFavorite;
import com.zer0drv.blog.interaction.domain.ArticleLike;
import com.zer0drv.blog.interaction.mapper.ArticleFavoriteMapper;
import com.zer0drv.blog.interaction.mapper.ArticleLikeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * {@link ArticleInteractionPort} 的 interaction 模块实现（issue #34 第三步）：
 * article_like / article_favorite 计数与访问者状态在本模块内查询，
 * 口径逐字节沿用自文章域 fillInteraction 收口前的实现。
 *
 * @author Yoruhaki
 */
@Component
@RequiredArgsConstructor
public class ArticleInteractionPortImpl implements ArticleInteractionPort {

    private final ArticleLikeMapper articleLikeMapper;
    private final ArticleFavoriteMapper articleFavoriteMapper;

    @Override
    public ArticleInteractionStats statsOf(long articleId, Long viewerId) {
        long likeCount = articleLikeMapper.selectCount(Wrappers.lambdaQuery(ArticleLike.class)
                .eq(ArticleLike::getArticleId, articleId));
        long favoriteCount = articleFavoriteMapper.selectCount(Wrappers.lambdaQuery(ArticleFavorite.class)
                .eq(ArticleFavorite::getArticleId, articleId));
        // 匿名一律 false 且不触发用户维度查询；登录人实时查关联表
        boolean liked = false;
        boolean favorited = false;
        if (Objects.nonNull(viewerId)) {
            liked = articleLikeMapper.selectCount(Wrappers.lambdaQuery(ArticleLike.class)
                    .eq(ArticleLike::getArticleId, articleId)
                    .eq(ArticleLike::getUserId, viewerId)) > 0;
            favorited = articleFavoriteMapper.selectCount(Wrappers.lambdaQuery(ArticleFavorite.class)
                    .eq(ArticleFavorite::getArticleId, articleId)
                    .eq(ArticleFavorite::getUserId, viewerId)) > 0;
        }
        return new ArticleInteractionStats(likeCount, favoriteCount, liked, favorited);
    }
}
