package com.zer0drv.blog.interaction;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zer0drv.blog.article.api.ArticleInteractionStats;
import com.zer0drv.blog.interaction.domain.ArticleFavorite;
import com.zer0drv.blog.interaction.domain.ArticleLike;
import com.zer0drv.blog.interaction.mapper.ArticleFavoriteMapper;
import com.zer0drv.blog.interaction.mapper.ArticleLikeMapper;
import com.zer0drv.blog.interaction.service.impl.ArticleInteractionPortImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * article.api.ArticleInteractionPort 实现纯单测（issue #34 第三步）：
 * 点赞/收藏计数实时查关联表；匿名 viewer（null）恒 false 且不触发用户维度查询——
 * 口径与收口前 ArticleServiceImpl.fillInteraction 逐字节等价。
 *
 * @author Yoruhaki
 */
@ExtendWith(MockitoExtension.class)
class ArticleInteractionPortImplTest {

    @Mock
    private ArticleLikeMapper articleLikeMapper;
    @Mock
    private ArticleFavoriteMapper articleFavoriteMapper;

    private ArticleInteractionPortImpl articleInteractionPort;

    @BeforeAll
    static void initTableInfo() {
        // 纯单测无 SqlSessionFactory：手动初始化实体 TableInfo，
        // 否则 LambdaQueryWrapper 解析列名时抛 "can not find lambda cache"
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ArticleLike.class);
        TableInfoHelper.initTableInfo(assistant, ArticleFavorite.class);
    }

    @BeforeEach
    void setUp() {
        articleInteractionPort = new ArticleInteractionPortImpl(articleLikeMapper, articleFavoriteMapper);
    }

    @Test
    void statsOf_anonymousViewer_countsOnlyAndFlagsFalse() {
        when(articleLikeMapper.selectCount(any())).thenReturn(7L);
        when(articleFavoriteMapper.selectCount(any())).thenReturn(3L);

        ArticleInteractionStats stats = articleInteractionPort.statsOf(10L, null);

        assertEquals(7L, stats.likeCount());
        assertEquals(3L, stats.favoriteCount());
        assertFalse(stats.liked());
        assertFalse(stats.favorited());
        // 匿名不触发用户维度查询：每表各 1 次计数
        verify(articleLikeMapper, times(1)).selectCount(any());
        verify(articleFavoriteMapper, times(1)).selectCount(any());
    }

    @Test
    void statsOf_loggedInViewer_queriesUserFlags() {
        // article_like：总数 7，本人已赞（1）
        when(articleLikeMapper.selectCount(any())).thenReturn(7L, 1L);
        // article_favorite：总数 3，本人未藏（0）
        when(articleFavoriteMapper.selectCount(any())).thenReturn(3L, 0L);

        ArticleInteractionStats stats = articleInteractionPort.statsOf(10L, 2L);

        assertEquals(7L, stats.likeCount());
        assertEquals(3L, stats.favoriteCount());
        assertTrue(stats.liked());
        assertFalse(stats.favorited());
        verify(articleLikeMapper, times(2)).selectCount(any());
        verify(articleFavoriteMapper, times(2)).selectCount(any());
    }
}
