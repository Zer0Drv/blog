package com.zer0drv.blog.article.domain;

import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.StatusCode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

/** Validated public search inputs, also used as bound SQL parameters. */
public record ArticleSearchCriteria(String keyword, String likePattern, Long categoryId, Long tagId,
                                    LocalDateTime publishedFrom, LocalDateTime publishedUntil,
                                    boolean fulltext, String effectiveSort, long page, long size) {
    public static ArticleSearchCriteria of(String keyword, long page, long size, Long categoryId, Long tagId,
                                          LocalDate from, LocalDate to, String sort, boolean fulltextEnabled) {
        String kw = keyword == null ? "" : keyword.strip();
        String requestedSort = sort == null ? "relevance" : sort;
        if (kw.length() > 200 || page < 1 || page > 100_000 || size < 1 || size > 100
                || categoryId != null && categoryId <= 0 || tagId != null && tagId <= 0
                || !Set.of("relevance", "newest", "oldest").contains(requestedSort)) {
            throw invalid("搜索参数无效：关键词最多 200 字，页码 1–100000，每页 1–100 条，排序为 relevance/newest/oldest");
        }
        LocalDate min = LocalDate.of(1000, 1, 1), max = LocalDate.of(9999, 12, 30);
        if (from != null && (from.isBefore(min) || from.isAfter(max))
                || to != null && (to.isBefore(min) || to.isAfter(max))
                || from != null && to != null && from.isAfter(to)) {
            throw invalid("发布时间范围无效");
        }
        // ngram_token_size defaults to 2. Short tokens and punctuation are literal searches,
        // avoiding dropped one-character terms and FULLTEXT operator/tokenization surprises.
        boolean fulltext = fulltextEnabled && !kw.isEmpty()
                && kw.codePoints().allMatch(c -> Character.isLetterOrDigit(c) || Character.isWhitespace(c))
                && java.util.Arrays.stream(kw.split("(?U)\\s+"))
                .allMatch(term -> term.codePointCount(0, term.length()) >= 2);
        String effectiveSort = "relevance".equals(requestedSort) && !fulltext ? "newest" : requestedSort;
        // A dedicated escape character works identically with MySQL and H2, including backslashes.
        String pattern = "%" + kw.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return new ArticleSearchCriteria(kw, pattern, categoryId, tagId,
                from == null ? null : from.atStartOfDay(), to == null ? null : to.plusDays(1).atStartOfDay(),
                fulltext, effectiveSort, page, size);
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(StatusCode.PARAM_INVALID.getCode(), message);
    }
}
