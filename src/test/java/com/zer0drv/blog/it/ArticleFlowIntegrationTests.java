package com.zer0drv.blog.it;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文章链路接口级集成测试：预置 AUTHOR + 分类 / 标签 → 登录 → 发布文章
 * → 详情断言且浏览量逐次自增 → 公开列表按关键字可查到。
 *
 * @author Yoruhaki
 */
class ArticleFlowIntegrationTests extends IntegrationTestSupport {

    @Test
    void publishThenDetailIncrementsViewCountAndListed() throws Exception {
        String authorName = unique("it_art_author_");
        seedUser(authorName, "文章作者", "AUTHOR");
        long categoryId = seedCategory(unique("集成分类"));
        long tagId = seedTag(unique("集成标签"));
        String bearer = bearerOf(authorName);

        // 发布（允许直接发布：status = PUBLISHED）
        String title = unique("集成文章标题");
        String body = """
                {"title":"%s","content":"# 集成测试正文","editorType":"MARKDOWN",
                 "categoryId":%d,"tagIds":[%d],"status":"PUBLISHED"}"""
                .formatted(title, categoryId, tagId);
        MvcResult created = mockMvc.perform(post("/articles")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andReturn();
        long articleId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").asLong();
        if (articleId <= 0) {
            throw new IllegalStateException("发布文章未返回有效 id");
        }

        // 详情（匿名可访问）：首次 viewCount = 1，再次访问自增为 2
        mockMvc.perform(get("/articles/{id}", articleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.title").value(title))
                .andExpect(jsonPath("$.data.editorType").value("MARKDOWN"))
                .andExpect(jsonPath("$.data.categoryName").isNotEmpty())
                .andExpect(jsonPath("$.data.viewCount").value(1));
        mockMvc.perform(get("/articles/{id}", articleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.viewCount").value(2));

        // 公开列表：keyword 用唯一标题，避免共享库中其他测试数据干扰
        mockMvc.perform(get("/articles").param("keyword", title))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(articleId))
                .andExpect(jsonPath("$.data.records[0].title").value(title))
                // 列表 VO 携带 editorType（前端行内导出按它决定 md/html）
                .andExpect(jsonPath("$.data.records[0].editorType").value("MARKDOWN"));

        // 作者本人文章列表同样可见
        mockMvc.perform(get("/articles/mine")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void publishAsNormalUserIsForbidden() throws Exception {
        String username = unique("it_art_user_");
        seedUser(username, "普通用户", "USER");
        String bearer = bearerOf(username);

        // 现状记录：@PreAuthorize 拒绝走 GlobalExceptionHandler，HTTP 200 + 业务码 40300
        // （与 URL 级 /admin/** 拒绝返回真实 403 不一致，属已知行为）
        mockMvc.perform(post("/articles")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"content\":\"y\",\"editorType\":\"MARKDOWN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40300"));
    }
}
