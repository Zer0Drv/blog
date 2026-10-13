package com.zer0drv.blog.article.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ArticleSlugger} 纯函数测试（issue #14）：格式校验口径与标题清洗规则的行为锁。
 *
 * @author Yoruhaki
 */
class ArticleSluggerTest {

    // ---------- isValid ----------

    @Test
    void isValid_acceptsLowerAlnumHyphenForm() {
        assertTrue(ArticleSlugger.isValid("hello-world"));
        assertTrue(ArticleSlugger.isValid("a"));
        assertTrue(ArticleSlugger.isValid("post-42"));
        assertTrue(ArticleSlugger.isValid("2024-annual-report"));
    }

    @Test
    void isValid_rejectsIllegalForms() {
        assertFalse(ArticleSlugger.isValid(null));
        assertFalse(ArticleSlugger.isValid(""));
        assertFalse(ArticleSlugger.isValid("Hello-World"), "大写不合法");
        assertFalse(ArticleSlugger.isValid("hello_world"), "下划线不合法");
        assertFalse(ArticleSlugger.isValid("hello world"), "空格不合法");
        assertFalse(ArticleSlugger.isValid("-hello"), "连字符开头不合法");
        assertFalse(ArticleSlugger.isValid("hello-"), "连字符结尾不合法");
        assertFalse(ArticleSlugger.isValid("hello--world"), "连续连字符不合法");
        assertFalse(ArticleSlugger.isValid("中文-slug"), "非 ASCII 不合法");
        assertFalse(ArticleSlugger.isValid("a".repeat(201)), "超过 200 字符不合法");
        assertTrue(ArticleSlugger.isValid("a".repeat(200)), "200 字符边界合法");
    }

    // ---------- clean ----------

    @Test
    void clean_basicAsciiTitle() {
        assertEquals("hello-world", ArticleSlugger.clean("Hello World!"));
        assertEquals("my-first-post-2024", ArticleSlugger.clean("My First Post 2024"));
    }

    @Test
    void clean_foldsAccentsAndDashes() {
        // NFKD 去变音符
        assertEquals("cafe-resume", ArticleSlugger.clean("Café Résumé"));
        // 下划线 / 连续斜杠折叠为单连字符
        assertEquals("a-b-c", ArticleSlugger.clean("a_b//c"));
        assertEquals("spring-boot-3-what-s-new", ArticleSlugger.clean("Spring Boot 3: What's New?"));
    }

    @Test
    void clean_mixedCjkKeepsAsciiParts() {
        // 中文字符段折叠为连字符，ASCII 部分保留
        assertEquals("java-17", ArticleSlugger.clean("升级 Java 17 记录"));
    }

    @Test
    void clean_pureCjkReturnsEmpty() {
        assertEquals("", ArticleSlugger.clean("纯中文标题"));
        assertEquals("", ArticleSlugger.clean(null));
        assertEquals("", ArticleSlugger.clean("   "));
        assertEquals("", ArticleSlugger.clean("！！！。。。"));
    }

    // ---------- truncateForSuffix ----------

    @Test
    void truncateForSuffix_reservesRoomAndAvoidsTrailingDash() {
        String base = "a".repeat(300);
        String truncated = ArticleSlugger.truncateForSuffix(base, "-12");
        // 后缀 3 字符 → 基底留 197 位，拼接后恰为 200
        assertEquals(197, truncated.length());
        assertEquals(ArticleSlugger.MAX_LENGTH, (truncated + "-12").length());

        // 截断落在连字符上时剥掉尾连字符，维持合法形态
        String dashed = "abc-" + "d".repeat(300);
        String t2 = ArticleSlugger.truncateForSuffix(dashed, "-2");
        assertFalse(t2.endsWith("-"));
        assertTrue(ArticleSlugger.isValid(t2 + "-2"));
    }
}
