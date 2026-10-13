package com.zer0drv.blog.article.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * slug 固定链接的纯函数工具（issue #14）：格式校验 + 任意文本清洗为 slug 基底。
 * 不引拼音库：清洗只做 NFKD 去变音符（Café→cafe）与 ASCII 白名单折叠，
 * 纯中文等清洗结果为空的输入由调用方兜底（post-{id}）。纯静态，无 Spring 依赖。
 *
 * @author Yoruhaki
 */
public final class ArticleSlugger {

    /**
     * slug 最大长度：与 article.slug 列（VARCHAR(200)）一致
     */
    public static final int MAX_LENGTH = 200;

    /**
     * 合法 slug：小写字母/数字段，段间单连字符连接（不以连字符开头/结尾，无连续连字符）
     */
    private static final Pattern VALID_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    /**
     * 清洗时的非法字符折叠：非小写字母/数字的连续区段折为一个连字符
     */
    private static final Pattern NON_SLUG_CHARS = Pattern.compile("[^a-z0-9]+");

    private ArticleSlugger() {
    }

    /**
     * 显式指定 slug 的格式校验：非空、≤{@link #MAX_LENGTH}、匹配小写字母/数字/连字符形态
     */
    public static boolean isValid(String slug) {
        return Objects.nonNull(slug)
                && !slug.isEmpty()
                && slug.length() <= MAX_LENGTH
                && VALID_PATTERN.matcher(slug).matches();
    }

    /**
     * 把任意文本清洗为 slug 基底：NFKD 去变音符 → 小写 → 非法字符折叠为连字符 →
     * 去首尾连字符。返回空串表示清洗不出任何 ASCII 字母数字（如纯中文标题），
     * 调用方需另行兜底。结果不保证唯一、不保证 ≤MAX_LENGTH（由调用方截断与消解冲突）。
     */
    public static String clean(String raw) {
        if (Objects.isNull(raw) || raw.isBlank()) {
            return "";
        }
        String asciiFolded = Normalizer.normalize(raw, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
        String cleaned = NON_SLUG_CHARS.matcher(asciiFolded).replaceAll("-");
        // 折叠天然不产生连续/首尾连字符之外再无修剪面：去首尾连字符即得规范形态
        int start = 0;
        int end = cleaned.length();
        while (start < end && cleaned.charAt(start) == '-') {
            start++;
        }
        while (end > start && cleaned.charAt(end - 1) == '-') {
            end--;
        }
        return cleaned.substring(start, end);
    }

    /**
     * 为追加序号后缀预留空间地截断基底：保证 {@code 截断结果 + suffix ≤ MAX_LENGTH}，
     * 且截断不落在连字符上（维持 {@link #isValid} 形态）
     */
    public static String truncateForSuffix(String base, String suffix) {
        int room = MAX_LENGTH - (Objects.isNull(suffix) ? 0 : suffix.length());
        String truncated = base.length() <= room ? base : base.substring(0, room);
        while (truncated.endsWith("-")) {
            truncated = truncated.substring(0, truncated.length() - 1);
        }
        return truncated;
    }
}
