package com.zer0drv.blog.it;

import com.zer0drv.blog.site.service.SiteConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P0 §2 comment+notify 系接口级集成测试：
 * 审核队列（开关开 → PENDING 不可见 → approve 可见且补发通知 / reject → FOLDED）、
 * 评论回收站（TRASH 列表 / restore 连带回复 / force 物理删）、通知偏好 GET/PUT、
 * 定时发布文章不可评论（§1.3 可见性谓词）。
 * 测试环境无 JavaMailSender：邮件走 dev-fallback 日志路径（approve 补发自然覆盖，不抛异常即通过）。
 *
 * @author Yoruhaki
 */
@Import(CommentP0IntegrationTests.SiteConfigStubConfiguration.class)
class CommentP0IntegrationTests extends IntegrationTestSupport {

    /**
     * SiteConfigService 顶替桩：C 合并后容器中存在真实实现 SiteConfigServiceImpl，
     * 这里以 @Primary mock 顶替（ObjectProvider.getIfAvailable() 命中 primary），
     * 并通过 REVIEW_REQUIRED 静态开关控制 comment.review_required，其余键返回默认值。
     */
    @TestConfiguration
    static class SiteConfigStubConfiguration {

        static final AtomicBoolean REVIEW_REQUIRED = new AtomicBoolean(false);

        @Bean
        @org.springframework.context.annotation.Primary
        SiteConfigService siteConfigService() {
            SiteConfigService mock = Mockito.mock(SiteConfigService.class);
            Mockito.lenient().when(mock.getBool(eq("comment.review_required"), anyBoolean()))
                    .thenAnswer(invocation -> REVIEW_REQUIRED.get());
            Mockito.lenient().when(mock.getValue(anyString(), anyString()))
                    .thenAnswer(invocation -> invocation.getArgument(1));
            return mock;
        }
    }

    @AfterEach
    void resetReviewSwitch() {
        SiteConfigStubConfiguration.REVIEW_REQUIRED.set(false);
    }

    private String seedAdminAndLogin() throws Exception {
        String admin = unique("it_cmt_admin_");
        seedUser(admin, "管理员", "ADMIN");
        return bearerOf(admin);
    }

    private long postComment(String bearer, long articleId, String content, Long parentId) throws Exception {
        String body = "{\"articleId\":" + articleId + ",\"content\":\"" + content + "\""
                + (parentId == null ? "" : ",\"parentId\":" + parentId) + "}";
        MvcResult result = mockMvc.perform(post("/comments")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").asLong();
    }

    private String commentStatus(long commentId) {
        return jdbcTemplate.queryForObject("SELECT status FROM `comment` WHERE id = ?", String.class, commentId);
    }

    @Test
    void reviewOn_createPending_approveThenVisibleAndNotified() throws Exception {
        SiteConfigStubConfiguration.REVIEW_REQUIRED.set(true);
        long authorId = seedUser(unique("it_rv_author_"), "作者", "AUTHOR");
        long articleId = seedArticle(authorId, unique("审核文章"), "PUBLISHED");
        String commenter = unique("it_rv_user_");
        seedUser(commenter, "评论人", "USER");
        String bearer = bearerOf(commenter);
        String adminBearer = seedAdminAndLogin();

        // 审核开关开：评论以 PENDING 落库，message 覆盖提示
        String content = unique("待审核评论");
        MvcResult created = mockMvc.perform(post("/comments")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"articleId\":" + articleId + ",\"content\":\"" + content + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.message").value("评论已提交，审核通过后展示"))
                .andReturn();
        long commentId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").asLong();
        assertThat(commentStatus(commentId)).isEqualTo("PENDING");

        // PENDING 公开列表不可见；不产生通知
        mockMvc.perform(get("/comments").param("articleId", String.valueOf(articleId)))
                .andExpect(jsonPath("$.data.total").value(0));
        Integer notifyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE article_id = ?", Integer.class, articleId);
        assertThat(notifyCount).isZero();

        // 管理端 PENDING 队列可见
        mockMvc.perform(get("/admin/comments")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .param("status", "PENDING").param("articleId", String.valueOf(articleId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(commentId));

        // approve → NORMAL，公开可见，且补发通知落库（邮件走 dev-fallback 不阻塞）
        mockMvc.perform(put("/admin/comments/" + commentId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
        assertThat(commentStatus(commentId)).isEqualTo("NORMAL");
        mockMvc.perform(get("/comments").param("articleId", String.valueOf(articleId)))
                .andExpect(jsonPath("$.data.total").value(1));
        Integer notified = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE article_id = ? AND user_id = ? AND type = 'COMMENT_REPLY'",
                Integer.class, articleId, authorId);
        assertThat(notified).isEqualTo(1);
    }

    @Test
    void reviewOn_reject_foldedAndInvisible() throws Exception {
        SiteConfigStubConfiguration.REVIEW_REQUIRED.set(true);
        long authorId = seedUser(unique("it_rj_author_"), "作者", "AUTHOR");
        long articleId = seedArticle(authorId, unique("拒审文章"), "PUBLISHED");
        String commenter = unique("it_rj_user_");
        seedUser(commenter, "评论人", "USER");
        String bearer = bearerOf(commenter);
        String adminBearer = seedAdminAndLogin();

        long commentId = postComment(bearer, articleId, unique("待拒评论"), null);
        assertThat(commentStatus(commentId)).isEqualTo("PENDING");

        // reject → FOLDED（不通知），公开不可见
        mockMvc.perform(put("/admin/comments/" + commentId + "/reject")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
        assertThat(commentStatus(commentId)).isEqualTo("FOLDED");
        mockMvc.perform(get("/comments").param("articleId", String.valueOf(articleId)))
                .andExpect(jsonPath("$.data.total").value(0));
        Integer notifyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE article_id = ?", Integer.class, articleId);
        assertThat(notifyCount).isZero();

        // 已拒绝的评论再 approve → PARAM_INVALID「仅待审核评论可执行该操作」
        mockMvc.perform(put("/admin/comments/" + commentId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(jsonPath("$.code").value("40023"))
                .andExpect(jsonPath("$.message").value("仅待审核评论可执行该操作"));
    }

    @Test
    void approve_normalComment_rejected() throws Exception {
        // 审核开关关闭（默认兜底）：评论直接 NORMAL，approve 非 PENDING → 40023
        long authorId = seedUser(unique("it_ap_author_"), "作者", "AUTHOR");
        long articleId = seedArticle(authorId, unique("直出文章"), "PUBLISHED");
        String commenter = unique("it_ap_user_");
        seedUser(commenter, "评论人", "USER");
        long commentId = postComment(bearerOf(commenter), articleId, unique("正常评论"), null);
        assertThat(commentStatus(commentId)).isEqualTo("NORMAL");
        String adminBearer = seedAdminAndLogin();

        mockMvc.perform(put("/admin/comments/" + commentId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(jsonPath("$.code").value("40023"))
                .andExpect(jsonPath("$.message").value("仅待审核评论可执行该操作"));
    }

    @Test
    void commentTrash_restoreCascadesReplies_forcePhysicalDelete() throws Exception {
        long authorId = seedUser(unique("it_ts_author_"), "作者", "AUTHOR");
        long articleId = seedArticle(authorId, unique("回收站文章"), "PUBLISHED");
        String commenter = unique("it_ts_user_");
        seedUser(commenter, "评论人", "USER");
        String bearer = bearerOf(commenter);
        String adminBearer = seedAdminAndLogin();

        long rootId = postComment(bearer, articleId, unique("楼主评论"), null);
        long replyId = postComment(bearer, articleId, unique("楼中回复"), rootId);

        // 删主评论连带逻辑删除回复 → 回收站可见
        mockMvc.perform(delete("/comments/" + rootId).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin/comments")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .param("status", "TRASH").param("articleId", String.valueOf(articleId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));

        // restore 主评论：连带恢复 deleted=1 的回复，两条都回到 NORMAL 且公开可见
        mockMvc.perform(post("/admin/comments/" + rootId + "/restore")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
        assertThat(commentStatus(rootId)).isEqualTo("NORMAL");
        assertThat(commentStatus(replyId)).isEqualTo("NORMAL");
        mockMvc.perform(get("/comments").param("articleId", String.valueOf(articleId)))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].replyCount").value(1));

        // 再次删除后 force：物理删除主评论+回复，回收站清空
        mockMvc.perform(delete("/admin/comments/" + rootId)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/admin/comments/" + rootId + "/force")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `comment` WHERE id IN (?, ?)", Integer.class, rootId, replyId);
        assertThat(remaining).isZero();
        // force 不存在的 id → COMMENT_NOT_EXIST
        mockMvc.perform(delete("/admin/comments/" + rootId + "/force")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(jsonPath("$.code").value("40041"));
    }

    @Test
    void commentOnScheduledArticle_rejected() throws Exception {
        // §1.3 可见性谓词：PUBLISHED 但 publish_time 在未来 → 不可评论
        long authorId = seedUser(unique("it_sc_author_"), "作者", "AUTHOR");
        String title = unique("定时文章");
        jdbcTemplate.update(
                "INSERT INTO article (title, summary, content, editor_type, cover, author_id, status, publish_time)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                title, title, title + "正文", "MARKDOWN", "", authorId, "PUBLISHED",
                java.sql.Timestamp.valueOf(LocalDateTime.now().plusDays(1)));
        long articleId = jdbcTemplate.queryForObject(
                "SELECT id FROM article WHERE title = ?", Long.class, title);
        String commenter = unique("it_sc_user_");
        seedUser(commenter, "评论人", "USER");

        mockMvc.perform(post("/comments")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(commenter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"articleId\":" + articleId + ",\"content\":\"抢沙发\"}"))
                .andExpect(jsonPath("$.code").value("40042"));
    }

    @Test
    void preferences_getAndUpdate() throws Exception {
        String username = unique("it_pf_user_");
        seedUser(username, "偏好用户", "USER");
        String bearer = bearerOf(username);

        // 默认开启（V8 列默认 1）
        mockMvc.perform(get("/users/me/preferences").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emailNotifyEnabled").value(true));

        // 关闭 → 返回最新值并落库
        mockMvc.perform(put("/users/me/preferences")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emailNotifyEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emailNotifyEnabled").value(false));
        mockMvc.perform(get("/users/me/preferences").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.data.emailNotifyEnabled").value(false));

        // 非布尔 → PARAM_INVALID；缺失 key → PARAM_INVALID
        mockMvc.perform(put("/users/me/preferences")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emailNotifyEnabled\":\"yes\"}"))
                .andExpect(jsonPath("$.code").value("40023"));
        mockMvc.perform(put("/users/me/preferences")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(jsonPath("$.code").value("40023"));

        // 重新开启
        mockMvc.perform(put("/users/me/preferences")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emailNotifyEnabled\":true}"))
                .andExpect(jsonPath("$.data.emailNotifyEnabled").value(true));
    }
}
