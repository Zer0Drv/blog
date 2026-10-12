package com.zer0drv.blog.it;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 收藏夹「置灰显示已下架」方案（方案 C）集成测试：
 * 已下架 / 定时中的文章仍留在我的收藏列表（listByIdsPreserveOrder 只滤已删除、
 * 不过滤可见性），但列表项必须携带可靠的 visible 标记供前端置灰——
 * visible 由装配层统一用 ArticleVisibility 谓词填充，不允许前端自行推断。
 *
 * @author Yoruhaki
 */
class FavoriteVisibilityIntegrationTests extends IntegrationTestSupport {

    @Test
    void favorites_keepInvisibleEntries_withReliableVisibleFlag() throws Exception {
        String author = unique("it_fav_author_");
        long authorId = seedUser(author, "收藏作者", "AUTHOR");
        String reader = unique("it_fav_reader_");
        long readerId = seedUser(reader, "收藏读者", "USER");

        long publishedId = seedArticle(authorId, unique("收藏可见文章"), "PUBLISHED");
        long offlineId = seedArticle(authorId, unique("收藏下架文章"), "OFFLINE");
        // 定时中：PUBLISHED 但 publish_time 在未来（seedArticle 的 PUBLISHED 只写当前时间，单独预置）
        String scheduledTitle = unique("收藏定时文章");
        jdbcTemplate.update(
                "INSERT INTO article (title, summary, content, editor_type, cover, author_id, status, publish_time)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                scheduledTitle, scheduledTitle, scheduledTitle + "正文", "MARKDOWN", "", authorId, "PUBLISHED",
                Timestamp.valueOf(LocalDateTime.now().withNano(0).plusHours(1)));
        long scheduledId = jdbcTemplate.queryForObject(
                "SELECT id FROM article WHERE title = ?", Long.class, scheduledTitle);

        // 按 published → offline → scheduled 顺序收藏；列表按收藏 id 倒序返回
        for (long articleId : new long[]{publishedId, offlineId, scheduledId}) {
            jdbcTemplate.update("INSERT INTO article_favorite (article_id, user_id) VALUES (?,?)",
                    articleId, readerId);
        }

        // 三条都仍在列表（不被可见性过滤），但 visible 各自正确：下架 / 定时中 = false，可见 = true
        mockMvc.perform(get("/articles/mine/favorites")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(reader)))
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.records[0].id").value(scheduledId))
                .andExpect(jsonPath("$.data.records[0].visible").value(false))
                .andExpect(jsonPath("$.data.records[1].id").value(offlineId))
                .andExpect(jsonPath("$.data.records[1].visible").value(false))
                .andExpect(jsonPath("$.data.records[2].id").value(publishedId))
                .andExpect(jsonPath("$.data.records[2].visible").value(true));
    }
}
