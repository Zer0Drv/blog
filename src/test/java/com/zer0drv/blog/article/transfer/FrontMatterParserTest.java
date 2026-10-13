package com.zer0drv.blog.article.transfer;

import com.zer0drv.blog.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FrontMatterParser 纯单测（issue #16）：解析（分隔线 / 剥离正文 / 非法 YAML）、
 * 渲染（字段顺序 / 中文不转义 / 特殊字符引号）与 round-trip 保真。
 *
 * @author Yoruhaki
 */
class FrontMatterParserTest {

    @Test
    void parse_noFrontMatter_returnsNull() {
        assertNull(FrontMatterParser.parse("# 标题\n\n正文"));
        assertNull(FrontMatterParser.parse(""));
        assertNull(FrontMatterParser.parse(null));
        // 缩进的 --- 不是 front matter 起始行（trim 后是，但此处首行不是独立分隔线场景）
        assertNull(FrontMatterParser.parse("正文\n---\n更多内容"));
    }

    @Test
    void parse_completeBlock_stripsMetaAndBody() {
        FrontMatterParser.FrontMatter fm = FrontMatterParser.parse(
                "---\ntitle: 你好\ntags:\n  - a\n  - b\n---\n\n正文第一行\n第二行\n");
        assertEquals("你好", fm.meta().get("title"));
        assertEquals(List.of("a", "b"), fm.meta().get("tags"));
        // front matter 与正文间的首个空行被剥离，其余原样保留
        assertEquals("正文第一行\n第二行\n", fm.body());
    }

    @Test
    void parse_docEndDelimiter_alsoCloses() {
        FrontMatterParser.FrontMatter fm = FrontMatterParser.parse("---\ntitle: t\n...\n正文");
        assertEquals("t", fm.meta().get("title"));
        assertEquals("正文", fm.body());
    }

    @Test
    void parse_crlfLineEndings() {
        FrontMatterParser.FrontMatter fm = FrontMatterParser.parse("---\r\ntitle: t\r\n---\r\n\r\n正文\r\n");
        assertEquals("t", fm.meta().get("title"));
        assertEquals("正文\r\n", fm.body());
    }

    @Test
    void parse_unclosedBlock_treatedAsNoFrontMatter() {
        // 没有结束行：按无 front matter 处理（可能只是正文开头的水平线）
        assertNull(FrontMatterParser.parse("---\ntitle: 没有结束行\n正文"));
    }

    @Test
    void parse_emptyBlock_emptyMeta() {
        FrontMatterParser.FrontMatter fm = FrontMatterParser.parse("---\n---\n正文");
        assertTrue(fm.meta().isEmpty());
        assertEquals("正文", fm.body());
    }

    @Test
    void parse_invalidYaml_rejected() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> FrontMatterParser.parse("---\ntitle: [未闭合\n---\n正文"));
        assertEquals("40023", e.getCode());
        assertTrue(e.getMessage().startsWith("front matter 解析失败"));
    }

    @Test
    void parse_nonMappingYaml_rejected() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> FrontMatterParser.parse("---\n- 只是列表\n- 不是键值对\n---\n正文"));
        assertEquals("40023", e.getCode());
        assertTrue(e.getMessage().contains("键值对"));
    }

    @Test
    void render_fieldOrderAndChineseUnescaped() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("title", "我的文章");
        meta.put("date", "2026-10-01T12:00:00");
        meta.put("tags", List.of("标签A", "标签B"));
        String rendered = FrontMatterParser.render(meta);
        assertTrue(rendered.startsWith("---\ntitle: 我的文章\n"), rendered);
        // 时间戳样式字符串会被加引号，保证 round-trip 读回 String
        assertTrue(rendered.contains("date: '2026-10-01T12:00:00'\n"), rendered);
        assertTrue(rendered.contains("tags:\n- 标签A\n- 标签B\n"), rendered);
        assertTrue(rendered.endsWith("---\n\n"), rendered);
    }

    @Test
    void render_specialCharsInTitle_quotedAndRoundTrip() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("title", "包含: 冒号 # 井号");
        String rendered = FrontMatterParser.render(meta);
        // snakeyaml 自动加引号，重新解析必须读回原值
        FrontMatterParser.FrontMatter fm = FrontMatterParser.parse(rendered + "正文");
        assertEquals("包含: 冒号 # 井号", fm.meta().get("title"));
        assertEquals("正文", fm.body());
    }

    @Test
    void roundTrip_exportThenImportKeepsAllValues() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("title", "round-trip");
        meta.put("date", "2026-01-02T03:04:05");
        meta.put("updated", "2026-01-03T04:05:06");
        meta.put("status", "PUBLISHED");
        meta.put("tags", List.of("a", "b"));
        meta.put("category", "随笔");
        meta.put("summary", "摘要");
        meta.put("cover", "https://example.com/cover.png");
        FrontMatterParser.FrontMatter fm = FrontMatterParser.parse(
                FrontMatterParser.render(meta) + "# 正文\n");
        assertEquals("round-trip", fm.meta().get("title"));
        assertEquals("2026-01-02T03:04:05", String.valueOf(fm.meta().get("date")));
        assertEquals("PUBLISHED", fm.meta().get("status"));
        assertEquals(List.of("a", "b"), fm.meta().get("tags"));
        assertEquals("随笔", fm.meta().get("category"));
        assertEquals("摘要", fm.meta().get("summary"));
        assertEquals("https://example.com/cover.png", fm.meta().get("cover"));
        assertEquals("# 正文\n", fm.body());
    }

    @Test
    void render_emptyMeta_stableEmptyBlock() {
        assertEquals("---\n---\n\n", FrontMatterParser.render(Map.of()));
    }
}
