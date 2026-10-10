package com.zer0drv.blog.it;

import com.zer0drv.blog.article.api.ArticleCascade;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 文章彻底删除的级联清场集成测试（issue #24 第三步）：
 * <ul>
 *   <li>级联完整性：容器内 ArticleCascade 实现数 ≥ 2，且 comment / interaction
 *       两个模块各自注册（防「某模块忘了实现」导致 forceDelete 静默漏删）；</li>
 *   <li>forceDelete 后 comment / comment_like / article_like / article_favorite
 *       全清（含已逻辑删除的评论及其点赞），article 自有的 article_tag /
 *       article_version 一并清理，autosave Redis 键按作者与操作者双份清理
 *       （Redis 深桩断言调用）。</li>
 * </ul>
 *
 * @author Yoruhaki
 */
class ArticleCascadeIntegrationTests extends IntegrationTestSupport {

    @Autowired
    private List<ArticleCascade> articleCascades;

    /**
     * Redis 深桩（IntegrationTestSupport.RedisStubConfiguration 的 @Primary mock），
     * 用于断言 autosave 键的 delete 调用
     */
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // ---------- 级联完整性（防漏实现兜底） ----------

    @Test
    void cascadeCompleteness_commentAndInteractionModulesBothRegistered() {
        assertTrue(articleCascades.size() >= 2,
                "ArticleCascade 实现不足 2 个，存在模块漏接级联清场：" + implementations());
        assertTrue(implementations().stream().anyMatch(name -> name.startsWith("com.zer0drv.blog.comment.")),
                "comment 模块缺少 ArticleCascade 实现：" + implementations());
        assertTrue(implementations().stream().anyMatch(name -> name.startsWith("com.zer0drv.blog.interaction.")),
                "interaction 模块缺少 ArticleCascade 实现：" + implementations());
    }

    private List<String> implementations() {
        return articleCascades.stream().map(cascade -> cascade.getClass().getName()).toList();
    }

    // ---------- forceDelete 全表级联清场 ----------

    @Test
    void forceDeletePurgesAllCascadeTablesAndAutosaveKey() throws Exception {
        String author = unique("it_casc_author_");
        long authorId = seedUser(author, "级联作者", "AUTHOR");
        String liker = unique("it_casc_liker_");
        long likerId = seedUser(liker, "点赞人", "USER");
        long articleId = seedArticle(authorId, unique("级联文章"), "PUBLISHED");

        // article 自有：article_tag + article_version
        long tagId = seedTag(unique("级联标签"));
        jdbcTemplate.update("INSERT INTO article_tag (article_id, tag_id) VALUES (?,?)", articleId, tagId);
        jdbcTemplate.update("INSERT INTO article_version (article_id, version, title, content, editor_type)"
                + " VALUES (?,1,?,?,?)", articleId, "快照标题", "快照正文", "MARKDOWN");

        // comment 模块：一条正常评论 + 一条已逻辑删除评论，两条各有点赞
        // （已删评论的点赞也必须随级联清掉——selectAllCommentIdsByArticleId 不区分 deleted）
        long normalCommentId = seedComment(articleId, likerId, unique("正常评论"), 0);
        long deletedCommentId = seedComment(articleId, likerId, unique("已删评论"), 1);
        jdbcTemplate.update("INSERT INTO comment_like (comment_id, user_id) VALUES (?,?)", normalCommentId, authorId);
        jdbcTemplate.update("INSERT INTO comment_like (comment_id, user_id) VALUES (?,?)", deletedCommentId, authorId);

        // interaction 模块：article_like + article_favorite
        jdbcTemplate.update("INSERT INTO article_like (article_id, user_id) VALUES (?,?)", articleId, likerId);
        jdbcTemplate.update("INSERT INTO article_favorite (article_id, user_id) VALUES (?,?)", articleId, likerId);

        // 作者本人彻底删除（无需先进回收站，force 允许任意状态行）
        mockMvc.perform(delete("/articles/{id}/force", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(author)))
                .andExpect(jsonPath("$.code").value("200"));

        assertRowCount("article", "id = " + articleId);
        assertRowCount("article_tag", "article_id = " + articleId);
        assertRowCount("article_version", "article_id = " + articleId);
        assertRowCount("`comment`", "article_id = " + articleId);
        assertRowCount("comment_like", "comment_id = " + normalCommentId);
        assertRowCount("comment_like", "comment_id = " + deletedCommentId);
        assertRowCount("article_like", "article_id = " + articleId);
        assertRowCount("article_favorite", "article_id = " + articleId);
        // 作者即操作者：autosave 只清一份
        Mockito.verify(stringRedisTemplate).delete("blog:autosave:" + articleId + ":" + authorId);
    }

    @Test
    void adminForceDeleteClearsAuthorAndOperatorAutosaveKeys() throws Exception {
        String author = unique("it_casc_author2_");
        long authorId = seedUser(author, "级联作者", "AUTHOR");
        String admin = unique("it_casc_admin_");
        long adminId = seedUser(admin, "管理员", "ADMIN");
        long articleId = seedArticle(authorId, unique("代删文章"), "PUBLISHED");
        jdbcTemplate.update("INSERT INTO `comment` (article_id, user_id, content) VALUES (?,?,?)",
                articleId, authorId, unique("待清评论"));
        jdbcTemplate.update("INSERT INTO article_like (article_id, user_id) VALUES (?,?)", articleId, authorId);

        mockMvc.perform(delete("/articles/{id}/force", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(admin)))
                .andExpect(jsonPath("$.code").value("200"));

        assertRowCount("`comment`", "article_id = " + articleId);
        assertRowCount("article_like", "article_id = " + articleId);
        // admin 代删他人文章：作者与操作者的 autosave 双键都清
        Mockito.verify(stringRedisTemplate).delete("blog:autosave:" + articleId + ":" + authorId);
        Mockito.verify(stringRedisTemplate).delete("blog:autosave:" + articleId + ":" + adminId);
    }

    private long seedComment(long articleId, long userId, String content, int deleted) {
        jdbcTemplate.update("INSERT INTO `comment` (article_id, user_id, content, deleted) VALUES (?,?,?,?)",
                articleId, userId, content, deleted);
        return jdbcTemplate.queryForObject("SELECT id FROM `comment` WHERE content = ?", Long.class, content);
    }

    private void assertRowCount(String table, String where) {
        Long rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Long.class);
        assertEquals(0L, rows == null ? -1L : rows, "彻底删除后 " + table + " 仍有残留行（" + where + "）");
    }
}
