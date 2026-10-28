package com.zer0drv.blog.article;

import com.zer0drv.blog.article.domain.ArticleSearchCriteria;
import com.zer0drv.blog.article.domain.ArticleSearchSnippet;
import com.zer0drv.blog.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArticleSearchTest {
    @Test void shortAndPunctuationQueriesAreLiteralEvenWhenFulltextIsEnabled() {
        for (String keyword : new String[]{"中", "a", "hi a", "100%", "a_b", "a!b", "C++", "a\\b", "<script>"}) {
            var q = query(keyword);
            assertFalse(q.fulltext(), keyword);
            assertEquals("newest", q.effectiveSort());
        }
        assertEquals("%100!%!_!!\\%", query("100%_!\\").likePattern());
        assertTrue(query("搜索 测试").fulltext());
        assertTrue(query("spring boot").fulltext());
    }

    @Test void rejectUnboundedOrInvalidInputsBeforeQuerying() {
        assertThrows(BusinessException.class, () -> query("x".repeat(201)));
        assertThrows(BusinessException.class, () -> ArticleSearchCriteria.of("x", 0, 10, null, null, null, null, "newest", false));
        assertThrows(BusinessException.class, () -> ArticleSearchCriteria.of("x", 1, 101, null, null, null, null, "newest", false));
        assertThrows(BusinessException.class, () -> ArticleSearchCriteria.of("x", Long.MAX_VALUE, 10, null, null, null, null, "newest", false));
        assertThrows(BusinessException.class, () -> ArticleSearchCriteria.of("x", 1, 10, -1L, null, null, null, "newest", false));
        assertThrows(BusinessException.class, () -> ArticleSearchCriteria.of("x", 1, 10, null, null, null, null, "title;--", false));
        assertThrows(BusinessException.class, () -> ArticleSearchCriteria.of("x", 1, 10, null, null, LocalDate.of(2025,2,1), LocalDate.of(2025,1,1), "newest", false));
        assertThrows(BusinessException.class, () -> ArticleSearchCriteria.of("x", 1, 10, null, null, null, LocalDate.MAX, "newest", false));
    }

    @Test void inclusiveDateUsesExclusiveNextDayAndPreservesOldestSort() {
        var q = ArticleSearchCriteria.of(null, 1, 10, null, null, LocalDate.of(2024,2,29), LocalDate.of(2024,2,29), "oldest", true);
        assertEquals("", q.keyword());
        assertFalse(q.fulltext());
        assertEquals("oldest", q.effectiveSort());
        assertEquals(LocalDate.of(2024,3,1).atStartOfDay(), q.publishedUntil());
    }

    @Test void snippetCentersBodyMatchAndDoesNotSplitSurrogatePairs() {
        String source = "😀".repeat(250) + "SEARCH 命中" + "尾".repeat(250);
        String snippet = ArticleSearchSnippet.of(source, "无关摘要", "search");
        assertTrue(snippet.contains("SEARCH 命中"));
        assertTrue(snippet.startsWith("…"));
        assertTrue(snippet.endsWith("…"));
        assertEquals(202, snippet.codePointCount(0, snippet.length()));
        assertFalse(Character.isLowSurrogate(snippet.charAt(1)));
    }

    @Test void snippetHandlesTokensMissingTextAndLiteralMarkupWithoutGeneratingHtml() {
        assertEquals("summary", ArticleSearchSnippet.of(null, "summary", "x"));
        assertEquals("", ArticleSearchSnippet.of(null, null, "x"));
        assertEquals("<script>x</script>", ArticleSearchSnippet.of("<script>x</script>", "", "<script>"));
        assertEquals("a b", ArticleSearchSnippet.of("a\n b", "", ""));
        assertTrue(ArticleSearchSnippet.of("a".repeat(300) + " boot " + "b".repeat(300), "", "spring boot").contains("boot"));
    }

    @Test void excerptKeepsLongKeywordWholeAndShowsSummaryOnlyHits() {
        String longKeyword = "关键词".repeat(60);
        String excerpt = ArticleSearchSnippet.of("a".repeat(300) + longKeyword + "z".repeat(300), "", longKeyword);
        assertTrue(excerpt.contains(longKeyword));
        assertEquals(202, excerpt.codePointCount(0, excerpt.length()));
        assertEquals("custom summary hit", ArticleSearchSnippet.of("unrelated body", "custom summary hit", "summary"));
        assertEquals("body hit", ArticleSearchSnippet.of("body hit", "summary hit", "hit"));
        assertEquals("unrelated body", ArticleSearchSnippet.of("unrelated body", "summary", "absent"));
    }

    @Test void fulltextPartialNgramHitCentersDeepBodyAndHighlightsOnlyDisplayedTerms() {
        String body = "前".repeat(350) + "搜索" + "尾".repeat(350);
        var excerpt = ArticleSearchSnippet.create("普通标题", body, "无关摘要", "搜索功能", true);
        assertTrue(excerpt.snippet().contains("搜索"));
        assertEquals(202, excerpt.snippet().codePointCount(0, excerpt.snippet().length()));
        assertEquals(List.of("搜索"), excerpt.highlightTerms());
        var literal = ArticleSearchSnippet.create("普通标题", body, "无关摘要", "搜索功能", false);
        assertFalse(literal.snippet().contains("搜索"));
        assertTrue(literal.highlightTerms().isEmpty());

        var titleHit = ArticleSearchSnippet.create("功能解析", "无关正文", "", "搜索功能", true);
        assertEquals(List.of("功能"), titleHit.highlightTerms());
    }

    @Test void strongerSummaryHitWinsAndLiteralMarkupNeverBecomesHighlightHtml() {
        var excerpt = ArticleSearchSnippet.create("普通标题", "前".repeat(300) + "搜索", "完整搜索功能说明", "搜索功能", true);
        assertEquals("完整搜索功能说明", excerpt.snippet());
        assertTrue(excerpt.highlightTerms().contains("搜索功能"));
        var markup = ArticleSearchSnippet.create("<img>", "literal <img> body", "", "<img>", false);
        assertEquals(List.of("<img>"), markup.highlightTerms());
        var supplementary = ArticleSearchSnippet.create("", "𠮷搜索", "", "𠮷搜索功能", true);
        assertTrue(supplementary.highlightTerms().contains("𠮷搜"));
        assertFalse(supplementary.highlightTerms().stream().anyMatch(term -> Character.isLowSurrogate(term.charAt(0))));
    }

    private static ArticleSearchCriteria query(String keyword) {
        return ArticleSearchCriteria.of(keyword, 1, 10, null, null, null, null, "relevance", true);
    }
}
