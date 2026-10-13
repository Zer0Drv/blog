package com.zer0drv.blog.it;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文章文件导入导出接口级集成测试（issue #16）：
 * 导出（GET /articles/{id}/export）——front matter 含标题/发布时间/状态/标签/分类等元数据、
 * html 按 editorType、可见性与详情一致（草稿对匿名/非作者报文章不存在、本人可导出）；
 * 批量导出（GET /articles/export?ids=）——zip 打包、ADMIN 全部 / AUTHOR 仅本人 / 匿名 401 / USER 40300；
 * 导入（POST /articles/import）——front matter 白名单自动填充（标签自动创建 / 分类按名匹配 /
 * date 回填 publish_time / status 忽略）、无 front matter 回退 H1 标题、.html 经 jsoup 清洗、
 * 非法扩展名 / 超大文件 / 非法 front matter 拒绝、zip 批量逐条容错与 Zip Slip 拒绝。
 *
 * @author Yoruhaki
 */
class ArticleImportExportIntegrationTests extends IntegrationTestSupport {

    /**
     * 预置指定编辑器类型的文章并返回 id（IntegrationTestSupport.seedArticle 固定 MARKDOWN）
     */
    private long seedArticleWithEditorType(long authorId, String title, String status,
                                           String editorType, String content) {
        Timestamp publishTime = "PUBLISHED".equals(status)
                ? Timestamp.valueOf(LocalDateTime.now().withNano(0)) : null;
        jdbcTemplate.update(
                "INSERT INTO article (title, summary, content, editor_type, cover, author_id, status, publish_time)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                title, title, content, editorType, "", authorId, status, publishTime);
        return jdbcTemplate.queryForObject("SELECT id FROM article WHERE title = ?", Long.class, title);
    }

    private static byte[] zipOf(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    private static Map<String, String> unzip(byte[] zip) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    /* ================================ 导出 ================================ */

    @Test
    void exportMarkdownContainsFrontMatter() throws Exception {
        String authorName = unique("it_exp_md_");
        long authorId = seedUser(authorName, "导出作者", "AUTHOR");
        String title = unique("导出文章");
        long tagId = seedTag(unique("导出标签"));
        long categoryId = seedCategory(unique("导出分类"));
        long articleId = seedArticleWithEditorType(authorId, title, "PUBLISHED", "MARKDOWN", "# 你好\n\n正文内容");
        jdbcTemplate.update("INSERT INTO article_tag (article_id, tag_id) VALUES (?,?)", articleId, tagId);
        jdbcTemplate.update("UPDATE article SET category_id = ? WHERE id = ?", categoryId, articleId);
        String slug = "it-exp-" + articleId;
        jdbcTemplate.update("UPDATE article SET slug = ? WHERE id = ?", slug, articleId);

        // 匿名可导出已发布文章；format 省略时 MARKDOWN → md
        MvcResult result = mockMvc.perform(get("/articles/{id}/export", articleId))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/markdown;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.startsWith("attachment"),
                                org.hamcrest.Matchers.containsString("filename*=UTF-8''"),
                                org.hamcrest.Matchers.endsWith(".md"))))
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.startsWith("---\ntitle: " + title + "\nslug: " + slug + "\ndate: '"), body);
        // 元数据：状态 / 标签名 / 分类名（种子数据 summary=标题，cover 空不输出）
        assertTrue(body.contains("status: PUBLISHED\n"), body);
        assertTrue(body.contains("tags:\n- "), body);
        assertTrue(body.contains("category: "), body);
        assertTrue(body.contains("summary: " + title + "\n"), body);
        assertTrue(body.contains("\n---\n\n"), body);
        assertTrue(body.endsWith("# 你好\n\n正文内容"), body);
    }

    @Test
    void exportHtmlByEditorTypeAndExplicitFormat() throws Exception {
        String authorName = unique("it_exp_html_");
        long authorId = seedUser(authorName, "导出作者", "AUTHOR");
        String title = unique("富文本文章");
        long articleId = seedArticleWithEditorType(authorId, title, "PUBLISHED", "RICHTEXT", "<p>富文本正文</p>");

        // RICHTEXT 缺省导出 html
        mockMvc.perform(get("/articles/{id}/export", articleId))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/html;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.endsWith(".html")));

        // 跨格式显式请求：不做渲染转换，直接给源文本
        MvcResult result = mockMvc.perform(get("/articles/{id}/export", articleId).param("format", "md"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/markdown;charset=UTF-8"))
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.endsWith("<p>富文本正文</p>"), body);

        // 非法 format → 参数错误码
        mockMvc.perform(get("/articles/{id}/export", articleId).param("format", "pdf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40023"));
    }

    @Test
    void exportInvisibleDraftRejectedLikeDetail() throws Exception {
        String authorName = unique("it_exp_draft_");
        long authorId = seedUser(authorName, "草稿作者", "AUTHOR");
        long articleId = seedArticleWithEditorType(authorId, unique("草稿文章"), "DRAFT", "MARKDOWN", "草稿正文");

        // 匿名：与详情一致报文章不存在（40030），HTTP 200 包业务码
        mockMvc.perform(get("/articles/{id}/export", articleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40030"));

        // 非作者登录用户同样不可见
        String otherName = unique("it_exp_other_");
        seedUser(otherName, "路人", "USER");
        mockMvc.perform(get("/articles/{id}/export", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(otherName)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40030"));

        // 作者本人可导出草稿
        mockMvc.perform(get("/articles/{id}/export", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(authorName)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/markdown;charset=UTF-8"));
    }

    /* ================================ 批量导出 ================================ */

    @Test
    void exportBatchZip_adminExportsAll() throws Exception {
        String adminName = unique("it_expz_admin_");
        seedUser(adminName, "管理员", "ADMIN");
        String authorName = unique("it_expz_author_");
        long authorId = seedUser(authorName, "作者", "AUTHOR");
        String titleA = unique("批量甲");
        String titleB = unique("批量乙");
        long idA = seedArticle(authorId, titleA, "PUBLISHED");
        long idB = seedArticleWithEditorType(authorId, titleB, "DRAFT", "MARKDOWN", "草稿正文");

        MvcResult result = mockMvc.perform(get("/articles/export")
                        .param("ids", String.valueOf(idA), String.valueOf(idB))
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(adminName)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/zip"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.endsWith("articles-export.zip")))
                .andReturn();

        Map<String, String> entries = unzip(result.getResponse().getContentAsByteArray());
        assertEquals(2, entries.size());
        assertTrue(entries.containsKey(titleA + ".md"), entries.keySet().toString());
        assertTrue(entries.containsKey(titleB + ".md"), entries.keySet().toString());
        assertTrue(entries.get(titleA + ".md").contains("status: PUBLISHED"));
        assertTrue(entries.get(titleB + ".md").contains("status: DRAFT"));
    }

    @Test
    void exportBatchZip_authorOnlyOwn() throws Exception {
        String authorName = unique("it_expz_own_");
        long authorId = seedUser(authorName, "作者", "AUTHOR");
        String otherName = unique("it_expz_other_");
        long otherId = seedUser(otherName, "另一位作者", "AUTHOR");
        long ownId = seedArticle(authorId, unique("本人文章"), "PUBLISHED");
        long othersId = seedArticle(otherId, unique("他人文章"), "PUBLISHED");

        // 本人文章可导
        mockMvc.perform(get("/articles/export").param("ids", String.valueOf(ownId))
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(authorName)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/zip"));

        // 混入他人文章：与不存在同语义 40030（不暴露存在性）
        mockMvc.perform(get("/articles/export")
                        .param("ids", String.valueOf(ownId), String.valueOf(othersId))
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(authorName)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40030"));
    }

    @Test
    void exportBatchZip_anonymousAndNormalUserRejected() throws Exception {
        String authorName = unique("it_expz_rej_");
        long authorId = seedUser(authorName, "作者", "AUTHOR");
        long articleId = seedArticle(authorId, unique("批量拒绝"), "PUBLISHED");

        // 匿名：该路径要求登录，安全链直接 401（区别于公开的单篇导出）
        mockMvc.perform(get("/articles/export").param("ids", String.valueOf(articleId)))
                .andExpect(status().isUnauthorized());

        // 普通 USER：@PreAuthorize 拒绝 → 40300
        String userName = unique("it_expz_user_");
        seedUser(userName, "普通用户", "USER");
        mockMvc.perform(get("/articles/export").param("ids", String.valueOf(articleId))
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userName)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40300"));

        // ids 为空 → 40023
        mockMvc.perform(get("/articles/export").param("ids", "")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(authorName)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40023"));
    }

    /* ================================ 导入 ================================ */

    @Test
    void importMarkdownCreatesDraftWithH1Title() throws Exception {
        String authorName = unique("it_imp_md_");
        seedUser(authorName, "导入作者", "AUTHOR");
        MockMultipartFile file = new MockMultipartFile("file", "笔记.md", "text/markdown",
                "# 导入标题\n\n第一行正文\n## 二级保留\n".getBytes(StandardCharsets.UTF_8));

        MvcResult result = mockMvc.perform(multipart("/articles/import").file(file)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(authorName)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.imported[0].title").value("导入标题"))
                .andExpect(jsonPath("$.data.failed").isEmpty())
                .andReturn();
        long articleId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("imported").path(0).path("id").asLong();

        // 一律 DRAFT 草稿；H1 行已从正文移除，其余原样保留
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, editor_type, content FROM article WHERE id = ?", articleId);
        assertEquals("DRAFT", row.get("status"));
        assertEquals("MARKDOWN", row.get("editor_type"));
        String content = (String) row.get("content");
        assertTrue(content.contains("第一行正文\n## 二级保留"), content);
        assertFalse(content.contains("# 导入标题"), content);
    }

    @Test
    void importFrontMatterFillsMetadata() throws Exception {
        String authorName = unique("it_imp_fm_");
        seedUser(authorName, "导入作者", "AUTHOR");
        String existingTag = unique("已有标签");
        long existingTagId = seedTag(existingTag);
        String categoryName = unique("导入分类");
        long categoryId = seedCategory(categoryName);
        String newTag = unique("新标签");
        String slug = "it-imp-fm-" + System.nanoTime();
        String content = "---\n"
                + "title: front matter 标题\n"
                + "slug: " + slug + "\n"
                + "date: '2025-03-04T05:06:07'\n"
                + "tags:\n  - " + existingTag + "\n  - " + newTag + "\n"
                + "category: " + categoryName + "\n"
                + "summary: 导入摘要\n"
                + "cover: https://example.com/cover.png\n"
                + "status: PUBLISHED\n"
                + "layout: post\n"
                + "---\n\n"
                + "# 正文一级标题\n\n正文 ![外链图](https://example.com/a.png)\n";
        MockMultipartFile file = new MockMultipartFile("file", "post.md", "text/markdown",
                content.getBytes(StandardCharsets.UTF_8));

        MvcResult result = mockMvc.perform(multipart("/articles/import").file(file)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(authorName)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.imported[0].title").value("front matter 标题"))
                // status 忽略提示与未知字段汇总提示
                .andExpect(jsonPath("$.data.imported[0].warnings",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("status"))))
                .andExpect(jsonPath("$.data.imported[0].warnings",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("layout"))))
                // 外链图片待转存清单
                .andExpect(jsonPath("$.data.imported[0].externalImages[0]")
                        .value("https://example.com/a.png"))
                .andReturn();
        long articleId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("imported").path(0).path("id").asLong();

        // 元数据落库：DRAFT / 分类 / 摘要 / 封面 / slug / date 回填 publish_time
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, category_id, summary, cover, slug, publish_time, content FROM article WHERE id = ?",
                articleId);
        assertEquals("DRAFT", row.get("status"));
        assertEquals(categoryId, ((Number) row.get("category_id")).longValue());
        assertEquals("导入摘要", row.get("summary"));
        assertEquals("https://example.com/cover.png", row.get("cover"));
        assertEquals(slug, row.get("slug"));
        assertEquals(Timestamp.valueOf("2025-03-04 05:06:07"), row.get("publish_time"));
        // front matter 已有标题：正文 H1 保留（round-trip 保真）
        assertTrue(((String) row.get("content")).contains("# 正文一级标题"), (String) row.get("content"));

        // 标签：已有标签命中 + 新标签自动创建
        Long newTagId = jdbcTemplate.queryForObject("SELECT id FROM tag WHERE name = ?", Long.class, newTag);
        assertTrue(newTagId > 0);
        Integer relationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_tag WHERE article_id = ? AND tag_id IN (?,?)",
                Integer.class, articleId, existingTagId, newTagId);
        assertEquals(2, relationCount);
    }

    @Test
    void importInvalidFrontMatterRejected() throws Exception {
        String authorName = unique("it_imp_badfm_");
        seedUser(authorName, "导入作者", "AUTHOR");
        MockMultipartFile file = new MockMultipartFile("file", "broken.md", "text/markdown",
                "---\ntitle: [未闭合\n---\n正文".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/articles/import").file(file)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(authorName)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40023"))
                .andExpect(jsonPath("$.message",
                        org.hamcrest.Matchers.startsWith("front matter 解析失败")));
    }

    @Test
    void importZipMixedResults() throws Exception {
        String authorName = unique("it_imp_zip_");
        seedUser(authorName, "导入作者", "AUTHOR");
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("第一.md", "# 第一篇\n\n正文");
        entries.put("第二.md", "---\ntitle: 第二篇\n---\n\n正文");
        entries.put("不支持的.exe", "MZ");
        MockMultipartFile file = new MockMultipartFile("file", "批量.zip", "application/zip",
                zipOf(entries));

        MvcResult result = mockMvc.perform(multipart("/articles/import").file(file)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(authorName)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.imported.length()").value(2))
                .andExpect(jsonPath("$.data.failed.length()").value(1))
                .andExpect(jsonPath("$.data.failed[0].name").value("不支持的.exe"))
                .andReturn();
        long firstId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("imported").path(0).path("id").asLong();
        assertEquals("DRAFT", jdbcTemplate.queryForObject(
                "SELECT status FROM article WHERE id = ?", String.class, firstId));
    }

    @Test
    void importZipSlipRejected() throws Exception {
        String authorName = unique("it_imp_slip_");
        seedUser(authorName, "导入作者", "AUTHOR");
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("../穿越.md", "# 正文");
        MockMultipartFile file = new MockMultipartFile("file", "evil.zip", "application/zip",
                zipOf(entries));

        mockMvc.perform(multipart("/articles/import").file(file)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(authorName)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40023"))
                .andExpect(jsonPath("$.message",
                        org.hamcrest.Matchers.containsString("非法路径")));
    }

    @Test
    void importMarkdownWithoutH1UsesFilename() throws Exception {
        String authorName = unique("it_imp_fn_");
        seedUser(authorName, "导入作者", "AUTHOR");
        MockMultipartFile file = new MockMultipartFile("file", "旅行随笔.txt", "text/plain",
                "没有标题的正文".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/articles/import").file(file)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(authorName)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.imported[0].title").value("旅行随笔"));
    }

    @Test
    void importHtmlIsSanitized() throws Exception {
        String authorName = unique("it_imp_html_");
        seedUser(authorName, "导入作者", "AUTHOR");
        MockMultipartFile file = new MockMultipartFile("file", "page.html", "text/html",
                "<p>安全内容</p><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));

        MvcResult result = mockMvc.perform(multipart("/articles/import").file(file)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(authorName)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.imported[0].title").value("page"))
                .andReturn();
        long articleId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("imported").path(0).path("id").asLong();
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT editor_type, status, content FROM article WHERE id = ?", articleId);
        assertEquals("RICHTEXT", row.get("editor_type"));
        assertEquals("DRAFT", row.get("status"));
        String content = (String) row.get("content");
        assertTrue(content.contains("<p>安全内容</p>"), content);
        assertFalse(content.contains("script"), content);
    }

    @Test
    void importRejectsBadExtensionAndOversize() throws Exception {
        String authorName = unique("it_imp_bad_");
        seedUser(authorName, "导入作者", "AUTHOR");
        String bearer = bearerOf(authorName);

        // 非法扩展名
        MockMultipartFile exe = new MockMultipartFile("file", "evil.exe", null,
                "MZ".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/articles/import").file(exe)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40023"))
                .andExpect(jsonPath("$.message").value("仅支持 .md/.markdown/.txt/.html 文件"));

        // 超过 2MB
        byte[] big = new byte[2 * 1024 * 1024 + 1];
        java.util.Arrays.fill(big, (byte) 'a');
        MockMultipartFile oversize = new MockMultipartFile("file", "big.md", "text/markdown", big);
        mockMvc.perform(multipart("/articles/import").file(oversize)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40023"))
                .andExpect(jsonPath("$.message").value("文件过大，最大 2MB"));
    }

    @Test
    void importAnonymousAndNormalUserRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "note.md", "text/markdown",
                "# 标题\n正文".getBytes(StandardCharsets.UTF_8));

        // 匿名：安全链直接 401（不进入统一响应体）
        mockMvc.perform(multipart("/articles/import").file(file))
                .andExpect(status().isUnauthorized());

        // 普通 USER：@PreAuthorize 拒绝走 GlobalExceptionHandler，HTTP 200 + 业务码 40300
        String username = unique("it_imp_user_");
        seedUser(username, "普通用户", "USER");
        mockMvc.perform(multipart("/articles/import").file(file)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40300"));
    }
}
