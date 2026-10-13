package com.zer0drv.blog.article.domain;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zer0drv.blog.article.api.ArticleStatus;
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
 * 并断言 apply(wrapper) 生成的 SQL 片段与 SQL 常量逐字对齐。
 *
 * <p>issue #15 扩展：可见性级别（PUBLIC / PASSWORD / PRIVATE）纳入谓词——
 * PASSWORD / PRIVATE 在任何消费形态（内存判定 / Wrapper / 原生 SQL 常量）下都不得
 * 对公众可见；新增与可见性级别无关的「已上线」判定 {@code isPublishedDue} 的分支覆盖。
 *
 * @author Yoruhaki
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
        return article(status, "PUBLIC", publishTime);
    }

    private static Article article(String status, String visibility, LocalDateTime publishTime) {
        Article article = new Article();
        article.setStatus(status);
        article.setVisibility(visibility);
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

    /* ---------- issue #15：可见性级别纳入公众谓词 ---------- */

    @Test
    void isVisible_passwordPublishedDue_false() {
        // 口令保护：已到点也不进公众出口（详情走口令门禁）
        assertFalse(ArticleVisibility.isVisible(
                article("PUBLISHED", "PASSWORD", NOW.minusMinutes(1)), FIXED_CLOCK));
    }

    @Test
    void isVisible_privatePublishedDue_false() {
        // 私密：对公众与「不存在」同语义
        assertFalse(ArticleVisibility.isVisible(
                article("PUBLISHED", "PRIVATE", NOW.minusMinutes(1)), FIXED_CLOCK));
    }

    @Test
    void isVisible_nullVisibility_treatedAsPublic() {
        // 列 NOT NULL DEFAULT 'PUBLIC'，空值仅可能来自内存新建尚未落库的实体
        // （单测 fixture / 保存前的临时对象），口径与列默认值一致；
        // Wrapper / SQL 形态面对的都是库内行（不存在 null），保持严格等值匹配
        assertTrue(ArticleVisibility.isVisible(
                article("PUBLISHED", null, NOW.minusMinutes(1)), FIXED_CLOCK));
    }

    /* ---------- issue #15：isPublishedDue 与可见性级别无关的「已上线」判定（详情口令门禁用） ---------- */

    @Test
    void isPublishedDue_ignoresVisibilityLevel() {
        assertTrue(ArticleVisibility.isPublishedDue(article("PUBLISHED", "PASSWORD", NOW.minusMinutes(1)), FIXED_CLOCK));
        assertTrue(ArticleVisibility.isPublishedDue(article("PUBLISHED", "PRIVATE", NOW.minusMinutes(1)), FIXED_CLOCK));
        assertTrue(ArticleVisibility.isPublishedDue(article("PUBLISHED", "PUBLIC", NOW.minusMinutes(1)), FIXED_CLOCK));
    }

    @Test
    void isPublishedDue_rejectsDraftOfflineScheduledAndNull() {
        assertFalse(ArticleVisibility.isPublishedDue(article("DRAFT", "PASSWORD", null), FIXED_CLOCK));
        assertFalse(ArticleVisibility.isPublishedDue(article("OFFLINE", "PASSWORD", NOW.minusMinutes(1)), FIXED_CLOCK));
        assertFalse(ArticleVisibility.isPublishedDue(article("PUBLISHED", "PASSWORD", NOW.plusMinutes(1)), FIXED_CLOCK));
        assertFalse(ArticleVisibility.isPublishedDue(article("PUBLISHED", "PUBLIC", null), FIXED_CLOCK));
        assertFalse(ArticleVisibility.isPublishedDue(null, FIXED_CLOCK));
    }

    @Test
    void apply_sqlSegmentContainsAllConditions() {
        LambdaQueryWrapper<Article> wrapper =
                ArticleVisibility.apply(Wrappers.lambdaQuery(Article.class), FIXED_CLOCK);
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("status ="), sql);
        assertTrue(sql.contains("publish_time IS NOT NULL"), sql);
        assertTrue(sql.contains("publish_time <="), sql);
        // issue #15：可见性条件入 Wrapper 形态
        assertTrue(sql.contains("visibility"), sql);
    }

    @Test
    void apply_returnsSameWrapperInstanceForChaining() {
        LambdaQueryWrapper<Article> wrapper = Wrappers.lambdaQuery(Article.class);
        assertSame(wrapper, ArticleVisibility.apply(wrapper, FIXED_CLOCK));
    }

    @Test
    void sqlConstant_containsFullPredicateWithExplicitDeleted() {
        // 逻辑删除不进内存判定：Wrapper / Mapper 查询由 MP @TableLogic 自动拼 deleted = 0；
        // 原生 SQL 绕过 MP 拦截，SQL 常量必须显式携带 deleted = 0；
        // issue #15：可见性条件同入常量，与内存 / Wrapper 形态同源
        assertEquals("status = 'PUBLISHED' AND publish_time IS NOT NULL AND publish_time <= NOW() AND deleted = 0"
                        + " AND visibility = 'PUBLIC'",
                ArticleVisibility.SQL);
    }
}
