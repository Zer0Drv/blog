package com.zer0drv.blog.article.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** A text-only excerpt. Consumers render the result as text, never as HTML. */
public final class ArticleSearchSnippet {
    private static final int LIMIT = 200;
    private static final int CASE_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private ArticleSearchSnippet() { }

    public static String of(String contentText, String summary, String keyword) {
        return create(null, contentText, summary, keyword, false).snippet();
    }

    public static Excerpt create(String title, String contentText, String summary, String keyword, boolean fulltext) {
        SearchTerms terms = terms(keyword, fulltext);
        String text = normalize(contentText);
        Match match = find(text, terms);
        String summaryText = normalize(summary);
        Match summaryMatch = find(summaryText, terms);
        // Prefer a complete summary phrase over a weaker body token/ngram; equal ranks prefer body.
        if (text.isEmpty() || summaryMatch != null && (match == null || summaryMatch.rank() < match.rank())) {
            text = summaryText;
            match = summaryMatch;
        }
        int count = text.codePointCount(0, text.length());
        int hit = match == null ? 0 : text.codePointCount(0, match.start());
        int matchLength = match == null ? 0 : text.codePointCount(match.start(), match.end());
        int leftContext = Math.min(60, Math.max(0, LIMIT - matchLength));
        int start = Math.min(Math.max(0, hit - leftContext), Math.max(0, count - LIMIT));
        int end = Math.min(count, start + LIMIT);
        String snippet = (start > 0 ? "…" : "")
                + text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end))
                + (end < count ? "…" : "");
        // Inspect only the returned text/title, never return unrelated query terms or hidden offsets.
        List<String> highlightTerms = terms.literals().stream().filter(term -> {
            Pattern pattern = literalPattern(term);
            return pattern.matcher(title == null ? "" : title).find() || pattern.matcher(snippet).find();
        }).toList();
        return new Excerpt(snippet, highlightTerms);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("(?U)\\s+", " ").strip();
    }

    private static SearchTerms terms(String keyword, boolean fulltext) {
        LinkedHashMap<String, Integer> candidates = new LinkedHashMap<>();
        if (keyword != null && !keyword.isBlank()) {
            candidates.put(keyword, 0);
            String[] words = keyword.split("(?U)\\s+");
            for (String word : words) if (!word.isEmpty()) candidates.putIfAbsent(word, 1);
            if (fulltext) {
                // Match the deployed ngram_token_size=2. Preserve Unicode code point boundaries.
                for (String word : words) {
                    int[] points = word.codePoints().toArray();
                    for (int i = 0; i + 1 < points.length; i++)
                        candidates.putIfAbsent(new String(points, i, 2), 2);
                }
            }
        }
        List<Tier> tiers = new ArrayList<>();
        for (int rank = 0; rank < 3; rank++) {
            int currentRank = rank;
            String expression = candidates.entrySet().stream().filter(entry -> entry.getValue() == currentRank)
                    .map(entry -> Pattern.quote(entry.getKey())).collect(Collectors.joining("|"));
            if (!expression.isEmpty()) tiers.add(new Tier(rank, Pattern.compile(expression, CASE_FLAGS)));
        }
        return new SearchTerms(List.copyOf(candidates.keySet()), tiers);
    }

    private static Match find(String text, SearchTerms terms) {
        // At most three body scans even for a long keyword; do not scan a large body per ngram.
        for (Tier tier : terms.tiers()) {
            var match = tier.pattern().matcher(text);
            if (match.find()) return new Match(match.start(), match.end(), tier.rank());
        }
        return null;
    }

    private static Pattern literalPattern(String term) { return Pattern.compile(Pattern.quote(term), CASE_FLAGS); }

    public record Excerpt(String snippet, List<String> highlightTerms) { }
    private record Match(int start, int end, int rank) { }
    private record Tier(int rank, Pattern pattern) { }
    private record SearchTerms(List<String> literals, List<Tier> tiers) { }
}
