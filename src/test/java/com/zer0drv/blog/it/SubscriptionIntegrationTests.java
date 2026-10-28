package com.zer0drv.blog.it;

import com.zer0drv.blog.subscription.repository.SubscriptionRepository;
import com.zer0drv.blog.subscription.service.SubscriptionService;
import com.zer0drv.blog.subscription.service.SubscriptionWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SubscriptionIntegrationTests extends IntegrationTestSupport {
    @Autowired SubscriptionWorker worker;
    @Autowired SubscriptionRepository repository;
    @Autowired SubscriptionService subscriptions;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void settleOtherTestsPublications() {
        // 集成测试共享 H2；其他用例留下的队列不属于本用例的推送对象。
        jdbcTemplate.update("UPDATE subscription_publication SET state='DONE'");
    }

    private long subscriber(String name, boolean email) throws Exception {
        long id = seedUser(name, "订阅读者", "USER");
        subscriptions.set(id, true);
        jdbcTemplate.update("UPDATE `user` SET article_subscribed_at=?,article_email_notify_enabled=? WHERE id=?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)), email ? 1 : 0, id);
        return id;
    }
    private long publish(String bearer, String visibility, String status) throws Exception {
        var dto = new java.util.HashMap<String, Object>(Map.of("title", unique("订阅新文章"), "content", "# 正文",
                "editorType", "MARKDOWN", "status", status, "visibility", visibility));
        if ("PASSWORD".equals(visibility)) dto.put("accessPassword", "pwd-1234");
        var response = mockMvc.perform(post("/articles").header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(dto)))
                .andExpect(jsonPath("$.code").value("200")).andReturn();
        return objectMapper.readTree(response.getResponse().getContentAsString()).path("data").asLong();
    }
    private String author() throws Exception {
        String name = unique("sub_author_");
        seedUser(name, "发布作者", "AUTHOR");
        return bearerOf(name);
    }
    private long notifications(long article, long user) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification WHERE article_id=? AND user_id=? AND type='ARTICLE_PUBLISHED'", Long.class, article, user);
    }

    @Test
    void subscriptionsRequireLoginAndStatsAreAdminOnly() throws Exception {
        mockMvc.perform(get("/subscriptions/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/subscriptions/me").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isUnauthorized());
        String name = unique("sub_api_");
        seedUser(name, "读者", "USER");
        String bearer = bearerOf(name);
        mockMvc.perform(put("/subscriptions/me").header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(jsonPath("$.code").value("200"));
        mockMvc.perform(get("/subscriptions/me").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.data.enabled").value(true)).andExpect(jsonPath("$.data.emailEnabled").value(false));
        mockMvc.perform(get("/users/me/preferences").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.data.articleNotifyEnabled").value(true));
        mockMvc.perform(get("/admin/subscriptions/stats").header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isForbidden());
        String admin = unique("sub_admin_");
        seedUser(admin, "管理员", "ADMIN");
        mockMvc.perform(get("/admin/subscriptions/stats").header(HttpHeaders.AUTHORIZATION, bearerOf(admin)))
                .andExpect(jsonPath("$.data.subscribers").isNumber());
    }

    @Test
    void publicationIsDurableDeduplicatedAndExcludesNonPublicContent() throws Exception {
        long reader = subscriber(unique("sub_reader_"), false);
        String bearer = author();
        long publicId = publish(bearer, "PUBLIC", "PUBLISHED");
        long privateId = publish(bearer, "PRIVATE", "PUBLISHED");
        long passwordId = publish(bearer, "PASSWORD", "PUBLISHED");
        long draftId = publish(bearer, "PUBLIC", "DRAFT");
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM subscription_publication WHERE article_id=?", Long.class, publicId));
        worker.processPending();
        worker.processPending();
        assertEquals(1L, notifications(publicId, reader));
        for (long id : new long[]{privateId, passwordId, draftId}) assertEquals(0L, notifications(id, reader));
        mockMvc.perform(post("/articles/{id}/publish", publicId).with(editRevision(publicId)).header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"修改正文\",\"content\":\"修改\",\"editorType\":\"MARKDOWN\",\"status\":\"PUBLISHED\"}"))
                .andExpect(jsonPath("$.code").value("200"));
        worker.processPending();
        assertEquals(1L, notifications(publicId, reader));
    }

    @Test
    void scheduledArticleWaitsAndPrivacyChangeStopsDelivery() throws Exception {
        long reader = subscriber(unique("sub_schedule_"), false);
        long id = publish(author(), "PUBLIC", "PUBLISHED");
        Timestamp future = Timestamp.valueOf(LocalDateTime.now().plusHours(1));
        jdbcTemplate.update("UPDATE article SET publish_time=? WHERE id=?", future, id);
        jdbcTemplate.update("UPDATE subscription_publication SET due_at=? WHERE article_id=?", future, id);
        worker.processPending();
        assertEquals(0L, notifications(id, reader));
        Timestamp past = Timestamp.valueOf(LocalDateTime.now().minusSeconds(2));
        jdbcTemplate.update("UPDATE article SET publish_time=?,visibility='PRIVATE' WHERE id=?", past, id);
        jdbcTemplate.update("UPDATE subscription_publication SET due_at=? WHERE article_id=?", past, id);
        worker.processPending();
        assertEquals(0L, notifications(id, reader));
        assertEquals("HIDDEN", jdbcTemplate.queryForObject("SELECT state FROM subscription_publication WHERE article_id=?", String.class, id));
    }

    @Test
    void emailRequiresConfirmationAndUnsubscribeIsAnonymousAndIdempotent() throws Exception {
        String name = unique("sub_confirm_");
        long reader = subscriber(name, false);
        String bearer = bearerOf(name);
        mockMvc.perform(post("/subscriptions/email-confirm").header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"000000\"}"))
                .andExpect(jsonPath("$.code").value("40018"));
        String key = "blog:email-code:article-subscription:" + name + "@it.local";
        Mockito.when(redis.opsForValue().get(key)).thenReturn("123456");
        mockMvc.perform(post("/subscriptions/email-confirm").header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                .andExpect(jsonPath("$.code").value("200"));
        Mockito.verify(redis).delete(key);
        long article = publish(author(), "PUBLIC", "PUBLISHED");
        worker.processPending();
        assertEquals("DEV", jdbcTemplate.queryForObject("SELECT mail_state FROM subscription_delivery WHERE article_id=? AND user_id=?", String.class, article, reader));
        String token = jdbcTemplate.queryForObject("SELECT article_unsubscribe_token FROM `user` WHERE id=?", String.class, reader);
        assertFalse(token.isBlank());
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/subscriptions/unsubscribe").contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("token", token))))
                    .andExpect(jsonPath("$.code").value("200"));
        }
        long later = publish(author(), "PUBLIC", "PUBLISHED");
        worker.processPending();
        assertEquals(0L, notifications(later, reader));
        assertFalse(subscriptions.state(reader).get("emailEnabled"));
        assertFalse(subscriptions.state(reader).get("enabled"));
    }

    @Test
    void scheduledPublicationDeliversAtDueTimeAndSkipsLateSubscribers() throws Exception {
        long early = subscriber(unique("sub_early_"), false);
        var body = Map.of("title", unique("定时订阅文章"), "content", "正文", "editorType", "MARKDOWN",
                "status", "PUBLISHED", "publishTime", LocalDateTime.now().plusHours(1).withNano(0).toString());
        var response = mockMvc.perform(post("/articles").header(HttpHeaders.AUTHORIZATION, author())
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(jsonPath("$.code").value("200")).andReturn();
        long id = objectMapper.readTree(response.getResponse().getContentAsString()).path("data").asLong();
        worker.processPending();
        assertEquals(0L, notifications(id, early));
        Timestamp due = Timestamp.valueOf(LocalDateTime.now().minusSeconds(5));
        jdbcTemplate.update("UPDATE article SET publish_time=? WHERE id=?", due, id);
        jdbcTemplate.update("UPDATE subscription_publication SET due_at=? WHERE article_id=?", due, id);
        long late = subscriber(unique("sub_late_"), false);
        jdbcTemplate.update("UPDATE `user` SET article_subscribed_at=? WHERE id=?", Timestamp.valueOf(LocalDateTime.now()), late);
        worker.processPending();
        worker.processPending();
        assertEquals(1L, notifications(id, early));
        assertEquals(0L, notifications(id, late));
    }

    @Test
    void emailQuotaIsAtomicAndRenewsAfterWindow() {
        long user = seedUser(unique("sub_budget_"), "额度读者", "USER");
        for (int i = 0; i < 10; i++) assertTrue(repository.reserveEmail(user));
        assertFalse(repository.reserveEmail(user));
        jdbcTemplate.update("UPDATE subscription_mail_budget SET window_started=? WHERE user_id=?", Timestamp.valueOf(LocalDateTime.now().minusHours(25)), user);
        assertTrue(repository.reserveEmail(user));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT attempts FROM subscription_mail_budget WHERE user_id=?", Integer.class, user));
    }

    @Test
    void publicationLeaseRejectsOtherWorkersAndCanRecoverExpiredClaim() {
        long id = seedArticle(seedUser(unique("sub_lease_author_"), "作者", "AUTHOR"), unique("租约文章"), "PUBLISHED");
        repository.enqueue(new com.zer0drv.blog.article.api.ArticlePublicationCandidate(id, LocalDateTime.now().minusSeconds(2)));
        assertTrue(repository.claim(id, LocalDateTime.now(), "first"));
        assertFalse(repository.claim(id, LocalDateTime.now(), "second"));
        jdbcTemplate.update("UPDATE subscription_publication SET lease_until=? WHERE article_id=?", Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)), id);
        assertTrue(repository.claim(id, LocalDateTime.now(), "second"));
        assertFalse(repository.heartbeat(id, "first"));
        repository.finish(id, "first", "DONE");
        assertEquals("RUNNING", jdbcTemplate.queryForObject("SELECT state FROM subscription_publication WHERE article_id=?", String.class, id));
        repository.finish(id, "second", "DONE");
    }
}
