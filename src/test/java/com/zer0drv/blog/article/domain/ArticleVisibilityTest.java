package com.zer0drv.blog.article.domain;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zer0drv.blog.article.enums.ArticleStatus;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ArticleVisibility 纯单测：固定 Clock 覆盖内存判定全部分支（含定时发布到点边界），
 * 并断言 apply(wrapper) 生成的 SQL 片段含完整三条件、SQL 常量显式携带 deleted = 0。
 */
class ArticleVisibilityTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZONE);
    private static final LocalDateTime NOW = LocalDateTime.now(FIXED_CLOCK);

    @BeforeAll
    static void initTableInfo() {
        // 纯单测无 MyBatis 容器：手动初始化实体 TableInfo，供 LambdaQueryWrapper 解析列名
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Article.class);
    }

    private static Article article(String status, LocalDateTime publishTime) {
        Article article = new Article();
        article.setStatus(status);
        article.setPublishTime(publishTime);
        return article;
    }

    @Test
    void isVisible_publishedAndDue_true() {
        Article article = article(ArticleStatus.PUBLISHED.name(), NOW.minusMinutes(1));
        assertTrue(ArticleVisibility.isVisible(article, FIXED_CLOCK));
    }

    @Test
    void isVisible_publishTimeExactlyNow_true() {
        // 边界：publish_time = now 已到点（谓词为 <= now），可见
        Article article = article(ArticleStatus.PUBLISHED.name(), NOW);
        assertTrue(ArticleVisibility.isVisible(article, FIXED_CLOCK));
    }

    @Test
    void isVisible_publishedButScheduledFuture_false() {
        // 定时发布未到点：PUBLISHED 但 publish_time 在未来，对公众不可见
        Article article = article(ArticleStatus.PUBLISHED.name(), NOW.plusMinutes(1));
        assertFalse(ArticleVisibility.isVisible(article, FIXED_CLOCK));
    }

    @Test
    void isVisible_nullPublishTime_false() {
        // publish_time 为空视为未发布
        assertFalse(ArticleVisibility.isVisible(article(ArticleStatus.PUBLISHED.name(), null), FIXED_CLOCK));
    }

    @Test
    void isVisible_draft_false() {
        assertFalse(ArticleVisibility.isVisible(article(ArticleStatus.DRAFT.name(), NOW.minusMinutes(1)), FIXED_CLOCK));
    }

    @Test
    void isVisible_offline_false() {
        assertFalse(ArticleVisibility.isVisible(article(ArticleStatus.OFFLINE.name(), NOW.minusMinutes(1)), FIXED_CLOCK));
    }

    @Test
    void isVisible_nullArticle_false() {
        assertFalse(ArticleVisibility.isVisible(null, FIXED_CLOCK));
    }

    @Test
    void isVisible_systemClockOverload_matchesRealtimeExpectation() {
        assertTrue(ArticleVisibility.isVisible(
                article(ArticleStatus.PUBLISHED.name(), LocalDateTime.now().minusSeconds(1))));
        assertFalse(ArticleVisibility.isVisible(
                article(ArticleStatus.PUBLISHED.name(), LocalDateTime.now().plusDays(1))));
    }

    @Test
    void apply_sqlSegmentContainsThreeConditions() {
        LambdaQueryWrapper<Article> wrapper =
                ArticleVisibility.apply(Wrappers.lambdaQuery(Article.class), FIXED_CLOCK);
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("status ="), sql);
        assertTrue(sql.contains("publish_time IS NOT NULL"), sql);
        assertTrue(sql.contains("publish_time <="), sql);
    }

    @Test
    void apply_returnsSameWrapperInstanceForChaining() {
        LambdaQueryWrapper<Article> wrapper = Wrappers.lambdaQuery(Article.class);
        assertSame(wrapper, ArticleVisibility.apply(wrapper, FIXED_CLOCK));
    }

    @Test
    void sqlConstant_containsFullPredicateWithExplicitDeleted() {
        // 逻辑删除不进内存判定：Wrapper / Mapper 查询由 MP @TableLogic 自动拼 deleted = 0；
        // 原生 SQL 绕过 MP 拦截，SQL 常量必须显式携带 deleted = 0
        assertEquals("status = 'PUBLISHED' AND publish_time IS NOT NULL AND publish_time <= NOW() AND deleted = 0",
                ArticleVisibility.SQL);
    }
}
