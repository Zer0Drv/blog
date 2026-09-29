package com.zer0drv.blog.it;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 评论链路接口级集成测试：预置已发布文章 → 用户发评论 → 公开列表可见；
 * 命中敏感词的评论按 M5 规则以 FOLDED 落库进入审核（接口正常返回但 message 覆盖提示，
 * 公开列表只展示 NORMAL，故不可见）。
 *
 * @author Yoruhaki
 */
class CommentFlowIntegrationTests extends IntegrationTestSupport {

    @Test
    void createCommentThenVisibleInList() throws Exception {
        long authorId = seedUser(unique("it_cmt_author_"), "评论作者", "AUTHOR");
        long articleId = seedArticle(authorId, unique("评论文章"), "PUBLISHED");
        String commenter = unique("it_cmt_user_");
        seedUser(commenter, "评论人甲", "USER");
        String bearer = bearerOf(commenter);

        String content = unique("这是一条正常评论");
        mockMvc.perform(post("/comments")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"articleId\":" + articleId + ",\"content\":\"" + content + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.message").value("success"));

        mockMvc.perform(get("/comments").param("articleId", String.valueOf(articleId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].content").value(content))
                .andExpect(jsonPath("$.data.records[0].user.nickname").value("评论人甲"));
    }

    @Test
    void sensitiveCommentIsFoldedAndHiddenFromPublicList() throws Exception {
        long authorId = seedUser(unique("it_cmt_author2_"), "评论作者乙", "AUTHOR");
        long articleId = seedArticle(authorId, unique("敏感词文章"), "PUBLISHED");
        String commenter = unique("it_cmt_user2_");
        seedUser(commenter, "评论人乙", "USER");
        String bearer = bearerOf(commenter);

        String word = unique("违禁词");
        seedSensitiveWord(word);
        String content = "这句话含有" + word + "需要审核";

        // 命中敏感词：接口正常返回 id，但 message 被覆盖为审核提示
        MvcResult created = mockMvc.perform(post("/comments")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"articleId\":" + articleId + ",\"content\":\"" + content + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.message").value("包含敏感内容，已进入审核"))
                .andReturn();
        long commentId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").asLong();

        // 落库状态为 FOLDED（折叠待审核）
        String dbStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM `comment` WHERE id = ?", String.class, commentId);
        assertThat(dbStatus).isEqualTo("FOLDED");

        // 公开列表仅展示 NORMAL：该评论不可见
        mockMvc.perform(get("/comments").param("articleId", String.valueOf(articleId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }
}
