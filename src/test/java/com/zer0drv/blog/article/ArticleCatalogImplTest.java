package com.zer0drv.blog.article;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zer0drv.blog.article.api.ArticleFeedEntry;
import com.zer0drv.blog.article.api.ArticleRef;
import com.zer0drv.blog.article.domain.Article;
import com.zer0drv.blog.article.enums.ArticleStatus;
import com.zer0drv.blog.article.mapper.ArticleMapper;
import com.zer0drv.blog.article.service.ArticleService;
import com.zer0drv.blog.article.service.impl.ArticleCatalogImpl;
import com.zer0drv.blog.article.vo.ArticleListVO;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.StatusCode;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ArticleCatalogImpl 边界测试（issue #24 第二步）：跨模块窄契约的谓词转发与投影映射。
 * 可见性 SQL 断言（status + publish_time &lt;= now）集中在本类，外模块单测不再叠加；
 * 本类在 article 模块内部，TableInfoHelper 初始化 Article 供 Wrapper 列名解析。
 *
 * @author Yoruhaki
 */
@ExtendWith(MockitoExtension.class)
class ArticleCatalogImplTest {

    @Mock
    private ArticleMapper articleMapper;
    @Mock
    private ArticleService articleService;

    private ArticleCatalogImpl articleCatalog;

    @BeforeAll
    static void initTableInfo() {
        // 纯单测无 SqlSessionFactory：手动初始化实体 TableInfo，供 Wrapper getSqlSegment 断言
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Article.class);
    }

    @BeforeEach
    void setUp() {
        articleCatalog = new ArticleCatalogImpl(articleMapper, articleService);
        // 装配回显：VO 只带 id，供顺序 / 透传断言
        lenient().when(articleService.assemble(any())).thenAnswer(invocation ->
                invocation.getArgument(0, List.class).stream().map(item -> {
                    ArticleListVO vo = new ArticleListVO();
                    vo.setId(((Article) item).getId());
                    return vo;
                }).toList());
    }

    private static Article articleOf(long id, long authorId, String status, LocalDateTime publishTime) {
        Article article = new Article();
        article.setId(id);
        article.setAuthorId(authorId);
        article.setTitle("标题" + id);
        article.setSummary("摘要" + id);
        article.setStatus(status);
        article.setPublishTime(publishTime);
        article.setUpdateTime(LocalDateTime.now().minusHours(1));
        return article;
    }

    private static Article visibleArticle(long id, long authorId) {
        return articleOf(id, authorId, ArticleStatus.PUBLISHED.name(), LocalDateTime.now().minusMinutes(1));
    }

    // ---------- isVisible / requireVisible ----------

    @Test
    void isVisible_publishedAndDue_true() {
        when(articleMapper.selectById(10L)).thenReturn(visibleArticle(10L, 1L));
        assertTrue(articleCatalog.isVisible(10L));
    }

    @Test
    void isVisible_draftOrScheduledOrMissing_false() {
        // 草稿
        when(articleMapper.selectById(11L))
                .thenReturn(articleOf(11L, 1L, ArticleStatus.DRAFT.name(), null));
        assertFalse(articleCatalog.isVisible(11L));
        // 定时发布未到点
        when(articleMapper.selectById(12L))
                .thenReturn(articleOf(12L, 1L, ArticleStatus.PUBLISHED.name(), LocalDateTime.now().plusHours(1)));
        assertFalse(articleCatalog.isVisible(12L));
        // publish_time 为空视为未发布
        when(articleMapper.selectById(13L))
                .thenReturn(articleOf(13L, 1L, ArticleStatus.PUBLISHED.name(), null));
        assertFalse(articleCatalog.isVisible(13L));
        // 不存在（含已删除，selectById 查不出）
        when(articleMapper.selectById(14L)).thenReturn(null);
        assertFalse(articleCatalog.isVisible(14L));
    }

    @Test
    void requireVisible_visible_returnsRef() {
        when(articleMapper.selectById(10L)).thenReturn(visibleArticle(10L, 1L));

        ArticleRef ref = articleCatalog.requireVisible(10L);

        assertEquals(10L, ref.id());
        assertEquals(1L, ref.authorId());
        assertEquals("标题10", ref.title());
    }

    @Test
    void requireVisible_invisibleOrMissing_throwsArticleNotExist() {
        when(articleMapper.selectById(10L))
                .thenReturn(articleOf(10L, 1L, ArticleStatus.OFFLINE.name(), LocalDateTime.now().minusDays(1)));
        BusinessException ex = assertThrows(BusinessException.class, () -> articleCatalog.requireVisible(10L));
        assertEquals(StatusCode.ARTICLE_NOT_EXIST.getCode(), ex.getCode());

        when(articleMapper.selectById(11L)).thenReturn(null);
        BusinessException ex2 = assertThrows(BusinessException.class, () -> articleCatalog.requireVisible(11L));
        assertEquals(StatusCode.ARTICLE_NOT_EXIST.getCode(), ex2.getCode());
    }

    // ---------- findRef / listRefsByIds（任意状态、未删除） ----------

    @Test
    void findRef_anyStatusPresent_missingOrNullIdEmpty() {
        // 草稿也能查到（findRef 不过滤可见性，供区分错误码 / 管理端用）
        when(articleMapper.selectById(10L))
                .thenReturn(articleOf(10L, 1L, ArticleStatus.DRAFT.name(), null));
        Optional<ArticleRef> ref = articleCatalog.findRef(10L);
        assertTrue(ref.isPresent());
        assertEquals(1L, ref.get().authorId());

        when(articleMapper.selectById(11L)).thenReturn(null);
        assertTrue(articleCatalog.findRef(11L).isEmpty());

        // null id 直接空，不触库
        assertTrue(articleCatalog.findRef(null).isEmpty());
    }

    @Test
    void listRefsByIds_mapsRefs_emptySkipsDb() {
        assertTrue(articleCatalog.listRefsByIds(List.of()).isEmpty());

        when(articleMapper.selectByIds(List.of(1L, 2L))).thenReturn(List.of(
                visibleArticle(1L, 7L), articleOf(2L, 8L, ArticleStatus.OFFLINE.name(), null)));
        List<ArticleRef> refs = articleCatalog.listRefsByIds(List.of(1L, 2L));
        assertEquals(2, refs.size());
        assertEquals("标题1", refs.get(0).title());
        assertEquals(8L, refs.get(1).authorId());
    }

    // ---------- pageVisibleByAuthors / countVisibleByAuthor ----------

    @Test
    void pageVisibleByAuthors_emptyAuthors_emptyPageWithoutDb() {
        PageResult<ArticleListVO> result = articleCatalog.pageVisibleByAuthors(List.of(), 1, 10);

        assertEquals(0, result.getTotal());
        assertTrue(result.getRecords().isEmpty());
        verifyNoInteractions(articleMapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageVisibleByAuthors_appliesVisibilityPredicateAndAssembles() {
        Page<Article> page = new Page<>(1, 10);
        page.setRecords(List.of(visibleArticle(3L, 8L)));
        page.setTotal(1);
        when(articleMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<ArticleListVO> result = articleCatalog.pageVisibleByAuthors(List.of(8L, 9L), 1, 10);

        // 谓词与 ArticleVisibility 一致：status + publish_time <= now + 作者集合
        ArgumentCaptor<LambdaQueryWrapper<Article>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(articleMapper).selectPage(any(Page.class), captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("status"), sql);
        assertTrue(sql.contains("publish_time") && sql.contains("<="), sql);
        assertTrue(sql.contains("author_id"), sql);
        // 装配走 ArticleService.assemble，结果透传
        assertEquals(1, result.getTotal());
        assertEquals(List.of(3L), result.getRecords().stream().map(ArticleListVO::getId).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void countVisibleByAuthor_appliesVisibilityPredicate() {
        when(articleMapper.selectCount(any())).thenReturn(5L);

        long count = articleCatalog.countVisibleByAuthor(7L);

        assertEquals(5L, count);
        ArgumentCaptor<LambdaQueryWrapper<Article>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(articleMapper).selectCount(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("status") && sql.contains("publish_time") && sql.contains("author_id"), sql);
    }

    // ---------- listVisibleLatest ----------

    @Test
    @SuppressWarnings("unchecked")
    void listVisibleLatest_mapsFeedEntriesWithLimitPage() {
        Page<Article> page = new Page<>(1, 20);
        page.setRecords(List.of(visibleArticle(1L, 7L), visibleArticle(2L, 8L)));
        when(articleMapper.selectPage(any(), any())).thenReturn(page);

        List<ArticleFeedEntry> entries = articleCatalog.listVisibleLatest(20);

        // limit 映射为第一页 size
        ArgumentCaptor<Page<Article>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        ArgumentCaptor<LambdaQueryWrapper<Article>> wrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(articleMapper).selectPage(pageCaptor.capture(), wrapperCaptor.capture());
        assertEquals(1, pageCaptor.getValue().getCurrent());
        assertEquals(20, pageCaptor.getValue().getSize());
        assertTrue(wrapperCaptor.getValue().getSqlSegment().contains("publish_time"),
                wrapperCaptor.getValue().getSqlSegment());
        assertEquals(2, entries.size());
        ArticleFeedEntry first = entries.get(0);
        assertEquals(1L, first.id());
        assertEquals(7L, first.authorId());
        assertEquals("标题1", first.title());
        assertEquals("摘要1", first.summary());
        assertTrue(first.publishTime() != null && first.updateTime() != null);
    }

    // ---------- listByIdsPreserveOrder ----------

    @Test
    void listByIdsPreserveOrder_keepsInputOrderAndFiltersMissing() {
        // 入参 [2, 3, 1]：3 已删除查不出；结果保持入参相对顺序 [2, 1]
        when(articleMapper.selectByIds(List.of(2L, 3L, 1L))).thenReturn(List.of(
                visibleArticle(1L, 7L), visibleArticle(2L, 8L)));

        List<ArticleListVO> vos = articleCatalog.listByIdsPreserveOrder(List.of(2L, 3L, 1L));

        assertEquals(List.of(2L, 1L), vos.stream().map(ArticleListVO::getId).toList());
        // 装配复用 ArticleService.assemble
        verify(articleService).assemble(any());
    }

    @Test
    void listByIdsPreserveOrder_emptySkipsDb() {
        assertTrue(articleCatalog.listByIdsPreserveOrder(List.of()).isEmpty());
        verifyNoInteractions(articleMapper);
    }
}
