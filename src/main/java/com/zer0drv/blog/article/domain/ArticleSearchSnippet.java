package com.zer0drv.blog.article.domain;

import java.util.regex.Pattern;

/** A text-only excerpt. Consumers render the result as text, never as HTML. */
public final class ArticleSearchSnippet {
    private static final int LIMIT = 200;

    private ArticleSearchSnippet() { }

    public static String of(String contentText, String summary, String keyword) {
        String text = normalize(contentText);
        Match match = find(text, keyword);
        String summaryText = normalize(summary);
        Match summaryMatch = find(summaryText, keyword);
        // LIKE also matches the custom summary; show that hit instead of unrelated body text.
        if (text.isEmpty() || match == null && summaryMatch != null) {
            text = summaryText;
            match = summaryMatch;
        }
        int count = text.codePointCount(0, text.length());
        int hit = match == null ? 0 : text.codePointCount(0, match.start());
        int matchLength = match == null ? 0 : text.codePointCount(match.start(), match.end());
        int leftContext = Math.min(60, Math.max(0, LIMIT - matchLength));
        int start = Math.min(Math.max(0, hit - leftContext), Math.max(0, count - LIMIT));
        int end = Math.min(count, start + LIMIT);
        return (start > 0 ? "…" : "")
                + text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end))
                + (end < count ? "…" : "");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("(?U)\\s+", " ").strip();
    }

    private static Match find(String text, String keyword) {
        Match match = indexOf(text, keyword);
        if (match == null && keyword != null) {
            for (String term : keyword.split("(?U)\\s+")) {
                match = indexOf(text, term);
                if (match != null) break;
            }
        }
        return match;
    }

    private static Match indexOf(String text, String term) {
        if (term == null || term.isBlank()) return null;
        var match = Pattern.compile(Pattern.quote(term), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text);
        return match.find() ? new Match(match.start(), match.end()) : null;
    }

    private record Match(int start, int end) { }
}
