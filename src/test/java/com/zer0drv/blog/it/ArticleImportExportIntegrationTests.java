package com.zer0drv.blog.it;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文章文件导入导出接口级集成测试：
 * 导出（GET /articles/{id}/export）——md 含 YAML front matter、html 按 editorType、
 * 可见性与详情一致（草稿对匿名/非作者报文章不存在、本人可导出）；
 * 导入（POST /articles/import）——.md 建 DRAFT 且标题取自首个 H1、.html 经 jsoup 清洗、
 * 非法扩展名 / 超大文件按参数错误码拒绝、匿名 401、普通 USER 40300。
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

    @Test
    void exportMarkdownContainsFrontMatter() throws Exception {
        String authorName = unique("it_exp_md_");
        long authorId = seedUser(authorName, "导出作者", "AUTHOR");
        String title = unique("导出文章");
        long articleId = seedArticleWithEditorType(authorId, title, "PUBLISHED", "MARKDOWN", "# 你好\n\n正文内容");

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
        assertTrue(body.startsWith("---\ntitle: " + title + "\ndate: "), body);
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

    @Test
    void importMarkdownCreatesDraftWithH1Title() throws Exception {
        String authorName = unique("it_imp_md_");
        seedUser(authorName, "导入作者", "AUTHOR");
        MockMultipartFile file = new MockMultipartFile("file", "笔记.md", "text/markdown",
                "# 导入标题\n\n第一行正文\n## 二级保留\n".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/articles/import").file(file)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(authorName)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.title").value("导入标题"))
                .andExpect(jsonPath("$.data.editorType").value("MARKDOWN"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                // H1 行已从正文移除，其余原样保留
                .andExpect(jsonPath("$.data.content").value(
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("第一行正文\n## 二级保留"),
                                org.hamcrest.Matchers.not(
                                        org.hamcrest.Matchers.containsString("# 导入标题")))));
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
                .andExpect(jsonPath("$.data.title").value("旅行随笔"))
                .andExpect(jsonPath("$.data.editorType").value("MARKDOWN"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
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
                .andExpect(jsonPath("$.data.title").value("page"))
                .andExpect(jsonPath("$.data.editorType").value("RICHTEXT"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn();
        String content = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("content").asString();
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
