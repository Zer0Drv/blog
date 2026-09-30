package com.zer0drv.blog.it;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEO 三件套接口级集成测试：rss.xml / atom.xml / sitemap.xml / robots.txt 匿名可访问，
 * 只出「可见文章」（PUBLISHED 且 publish_time <= now），定时中与草稿文章不出现。
 *
 * @author Yoruhaki
 */
class SeoIntegrationTests extends IntegrationTestSupport {

    /**
     * 调试：转义非 ASCII（surefire 控制台编码不可靠）
     */
    private static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c < 128 && c != '\n') {
                sb.append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else {
                sb.append(String.format("\\u%04x", (int) c));
            }
        }
        return sb.toString();
    }

    /**
     * 预置可见文章（PUBLISHED 且 publish_time 在过去 5 秒）并返回 id。
     * 注意不能用 now()：H2 的 DATETIME 对小数秒四舍五入（MySQL 同行为），
     * 同一秒内插入 + 查询时 publish_time <= now() 可能不成立（测试环境假象）。
     */
    private long seedVisibleArticle(long authorId, String title) {
        jdbcTemplate.update(
                "INSERT INTO article (title, summary, content, editor_type, cover, author_id, status, publish_time)"
                        + " VALUES (?,?,?,?,?,?,'PUBLISHED',?)",
                title, title, title + "正文", "MARKDOWN", "", authorId,
                Timestamp.valueOf(LocalDateTime.now().minusSeconds(5)));
        return jdbcTemplate.queryForObject("SELECT id FROM article WHERE title = ?", Long.class, title);
    }

    /**
     * 预置定时中文章（status=PUBLISHED 但 publish_time 在未来）并返回 id
     */
    private long seedScheduledArticle(long authorId, String title) {
        jdbcTemplate.update(
                "INSERT INTO article (title, summary, content, editor_type, cover, author_id, status, publish_time)"
                        + " VALUES (?,?,?,?,?,?,'PUBLISHED',?)",
                title, title, title + "正文", "MARKDOWN", "", authorId,
                Timestamp.valueOf(LocalDateTime.now().plusDays(1)));
        return jdbcTemplate.queryForObject("SELECT id FROM article WHERE title = ?", Long.class, title);
    }

    @Test
    void rssAndAtomContainOnlyVisibleArticles() throws Exception {
        long authorId = seedUser(unique("it_seo_author_"), "SEO作者", "AUTHOR");
        String publishedTitle = unique("SEO已发布文章");
        long publishedId = seedVisibleArticle(authorId, publishedTitle);
        String scheduledTitle = unique("SEO定时中文章");
        seedScheduledArticle(authorId, scheduledTitle);
        String draftTitle = unique("SEO草稿文章");
        seedArticle(authorId, draftTitle, "DRAFT");

        // RSS：匿名 200 + content-type + 含可见文章标题与链接，不含定时中/草稿
        MvcResult rss = mockMvc.perform(get("/rss.xml"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/rss+xml"))
                .andReturn();
        String rssBody = rss.getResponse().getContentAsString();
        assertTrue(rssBody.contains(publishedTitle), "RSS 应含已发布文章标题");
        assertTrue(rssBody.contains("/article/" + publishedId), "RSS 应含文章链接");
        assertTrue(rssBody.contains("http://localhost:5173"), "RSS channel link 应取 site.base_url");
        assertFalse(rssBody.contains(scheduledTitle), "RSS 不应含定时中文章");
        assertFalse(rssBody.contains(draftTitle), "RSS 不应含草稿");

        // Atom：同上
        MvcResult atom = mockMvc.perform(get("/atom.xml"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/atom+xml"))
                .andReturn();
        String atomBody = atom.getResponse().getContentAsString();
        assertTrue(atomBody.contains(publishedTitle), "Atom 应含已发布文章标题");
        assertFalse(atomBody.contains(scheduledTitle), "Atom 不应含定时中文章");
    }

    @Test
    void sitemapContainsVisibleArticleUrlsOnly() throws Exception {
        long authorId = seedUser(unique("it_seo_sm_"), "Sitemap作者", "AUTHOR");
        long publishedId = seedVisibleArticle(authorId, unique("SM已发布"));
        long scheduledId = seedScheduledArticle(authorId, unique("SM定时中"));

        MvcResult sitemap = mockMvc.perform(get("/sitemap.xml"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/xml"))
                .andReturn();
        String body = sitemap.getResponse().getContentAsString();
        assertTrue(body.contains("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">"));
        assertTrue(body.contains("<loc>http://localhost:5173/</loc>"), "sitemap 应含首页");
        assertTrue(body.contains("/article/" + publishedId + "</loc>"),
                () -> "sitemap 应含可见文章 URL, publishedId=" + publishedId + ", body=" + esc(body));
        assertFalse(body.contains("/article/" + scheduledId + "</loc>"), "sitemap 不应含定时中文章");
    }

    @Test
    void robotsDeclaresSitemap() throws Exception {
        MvcResult robots = mockMvc.perform(get("/robots.txt"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andReturn();
        String body = robots.getResponse().getContentAsString();
        assertTrue(body.contains("User-agent: *"));
        assertTrue(body.contains("Allow: /"));
        assertTrue(body.contains("Sitemap: http://localhost:5173/sitemap.xml"));
    }
}
