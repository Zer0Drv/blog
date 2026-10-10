package com.zer0drv.blog.interaction.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zer0drv.blog.article.api.ArticleCascade;
import com.zer0drv.blog.interaction.domain.ArticleFavorite;
import com.zer0drv.blog.interaction.domain.ArticleLike;
import com.zer0drv.blog.interaction.mapper.ArticleFavoriteMapper;
import com.zer0drv.blog.interaction.mapper.ArticleLikeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * interaction 模块的文章级联清场实现（issue #24 第三步）：文章彻底删除时，
 * 物理清理 article_like + article_favorite。
 *
 * @author Yoruhaki
 */
@Component
@RequiredArgsConstructor
public class InteractionArticleCascade implements ArticleCascade {

    private final ArticleLikeMapper articleLikeMapper;
    private final ArticleFavoriteMapper articleFavoriteMapper;

    @Override
    public void purgeByArticleId(long articleId) {
        articleLikeMapper.delete(Wrappers.lambdaQuery(ArticleLike.class)
                .eq(ArticleLike::getArticleId, articleId));
        articleFavoriteMapper.delete(Wrappers.lambdaQuery(ArticleFavorite.class)
                .eq(ArticleFavorite::getArticleId, articleId));
    }
}
