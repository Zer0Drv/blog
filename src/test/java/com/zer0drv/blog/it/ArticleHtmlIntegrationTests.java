package com.zer0drv.blog.it;

import com.zer0drv.blog.seo.service.FrontendShell;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ArticleHtmlIntegrationTests extends IntegrationTestSupport {
    @MockitoBean
    private FrontendShell frontend;

    @BeforeEach
    void shell() throws Exception {
        when(frontend.load()).thenReturn("""
                <!doctype html><html><head><title>Old</title><meta property="og:title" content="Old">
                <link rel="canonical" href="https://old.invalid"></head>
                <body><div id="app"></div><script type="module" src="/assets/current.js"></script></body></html>
                """);
    }

    @Test
    void rawHtmlUsesPublicArticleAndCanonicalSlugWithoutExecutingJavascript() throws Exception {
        long id = seedArticle(seedUser(unique("html"), "作者", "AUTHOR"), "公开 <b>标题</b>", "PUBLISHED");
        String slug = "html-public-" + id;
        jdbcTemplate.update("UPDATE article SET slug=?, summary=?, publish_time=? WHERE id=?",
                slug, "摘要 <img src=x onerror=alert(1)> \"quotes\"",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)), id);
        for (String key : new String[]{Long.toString(id), slug}) {
            String body = mockMvc.perform(get("/article/" + key).header("Host", "attacker.invalid"))
                    .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/html"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andReturn().getResponse().getContentAsString();
            var doc = Jsoup.parse(body);
            assertTrue(doc.title().startsWith("公开 标题 - "));
            assertEquals(1, doc.select("meta[property=og:title]").size());
            assertEquals("http://localhost:5173/article/" + slug, doc.selectFirst("link[rel=canonical]").attr("href"));
            assertEquals("摘要 \"quotes\"", doc.selectFirst("meta[name=description]").attr("content"));
            assertEquals("/assets/current.js", doc.selectFirst("script").attr("src"));
            assertFalse(body.contains("attacker.invalid"));
            assertTrue(doc.select("[onerror], img, meta[name=robots]").isEmpty());
        }
    }

    @Test
    void everyNonpublicStateAndMissingKeyHaveNoArticleMetadata() throws Exception {
        long id = seedArticle(seedUser(unique("hidden"), "作者", "AUTHOR"), "不得泄露的标题", "PUBLISHED");
        String slug = "html-hidden-" + id;
        jdbcTemplate.update("UPDATE article SET slug=?, summary=?, publish_time=? WHERE id=?",
                slug, "不得泄露的摘要", Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)), id);
        for (String visibility : new String[]{"PRIVATE", "PASSWORD"}) {
            jdbcTemplate.update("UPDATE article SET visibility=? WHERE id=?", visibility, id);
            assertHidden(Long.toString(id));
            assertHidden(slug);
            String authenticated = mockMvc.perform(get("/article/" + slug).with(jwt()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertFalse(authenticated.contains("不得泄露"));
        }
        jdbcTemplate.update("UPDATE article SET visibility='PUBLIC' WHERE id=?", id);
        for (String state : new String[]{"DRAFT", "OFFLINE"}) {
            jdbcTemplate.update("UPDATE article SET status=? WHERE id=?", state, id);
            assertHidden(slug);
        }
        jdbcTemplate.update("UPDATE article SET status='PUBLISHED', publish_time=? WHERE id=?",
                Timestamp.valueOf(LocalDateTime.now().plusDays(1)), id);
        assertHidden(slug);
        jdbcTemplate.update("UPDATE article SET publish_time=?, deleted=1 WHERE id=?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)), id);
        assertHidden(slug);
        assertHidden("does-not-exist");
        assertHidden("999999999999999999999999999999");
    }

    private void assertHidden(String key) throws Exception {
        String body = mockMvc.perform(get("/article/" + key)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var doc = Jsoup.parse(body);
        assertFalse(body.contains("不得泄露"));
        assertTrue(doc.select("link[rel=canonical]").isEmpty());
        assertEquals("noindex, nofollow", doc.selectFirst("meta[name=robots]").attr("content"));
        assertEquals(1, doc.select("#app").size());
    }

    @Test
    void unavailableShellFailsClosedWithoutCaching() throws Exception {
        when(frontend.load()).thenThrow(new IOException("private upstream detail"));
        String body = mockMvc.perform(get("/article/example")).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("private upstream"));
    }
}
