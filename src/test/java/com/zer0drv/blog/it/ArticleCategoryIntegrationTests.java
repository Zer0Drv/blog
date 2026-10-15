package com.zer0drv.blog.it;

import com.zer0drv.blog.article.domain.Article;
import com.zer0drv.blog.article.mapper.ArticleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #57：通过真实路由、MyBatis-Plus SQL 与 H2 重读验证分类保存/恢复契约。
 */
class ArticleCategoryIntegrationTests extends IntegrationTestSupport {

    @Autowired
    private ArticleMapper articleMapper;

    @ParameterizedTest
    @ValueSource(strings = {",\"categoryId\":null", ""})
    void fullSaveClearsCategoryForExplicitNullAndOmittedField(String categoryField) throws Exception {
        String author = unique("it_category_clear_");
        long authorId = seedUser(author, "分类作者", "AUTHOR");
        String bearer = bearerOf(author);
        long category = seedCategory(unique("待清空分类"));
        long id = categorizedArticle(authorId, category);
        jdbcTemplate.update("UPDATE article SET visibility = 'PASSWORD', access_password_hash = ? WHERE id = ?",
                "$2a$existing-password-hash", id);

        save(id, bearer, categoryField);

        assertCategory(id, bearer, null);
        assertEquals("PUBLISHED", jdbcTemplate.queryForObject("SELECT status FROM article WHERE id = ?", String.class, id));
        assertEquals("PASSWORD", jdbcTemplate.queryForObject("SELECT visibility FROM article WHERE id = ?", String.class, id));
        assertEquals("$2a$existing-password-hash", jdbcTemplate.queryForObject(
                "SELECT access_password_hash FROM article WHERE id = ?", String.class, id));
        assertEquals(category, versionCategory(id, 1));
    }

    @Test
    void assignsCategoryFromNullAndChangesBetweenCategories() throws Exception {
        String author = unique("it_category_assign_");
        long authorId = seedUser(author, "分类作者", "AUTHOR");
        String bearer = bearerOf(author);
        long id = seedArticle(authorId, unique("未分类文章"), "DRAFT");
        long categoryA = seedCategory(unique("分类A"));
        long categoryB = seedCategory(unique("分类B"));

        save(id, bearer, ",\"categoryId\":" + categoryA);
        assertCategory(id, bearer, categoryA);
        assertNull(versionCategory(id, 1));
        save(id, bearer, ",\"categoryId\":" + categoryB);
        assertCategory(id, bearer, categoryB);
        assertEquals(categoryA, versionCategory(id, 2));
    }

    @Test
    void restoresUncategorizedVersionAndSnapshotsCurrentCategoryWithoutChangingPublication() throws Exception {
        String author = unique("it_category_restore_null_");
        long authorId = seedUser(author, "分类作者", "AUTHOR");
        String bearer = bearerOf(author);
        String title = unique("未分类原稿");
        long id = seedArticle(authorId, title, "PUBLISHED");
        long category = seedCategory(unique("恢复前分类"));
        Map<String, Object> publication = jdbcTemplate.queryForMap(
                "SELECT status, publish_time, author_id, visibility FROM article WHERE id = ?", id);
        save(id, bearer, ",\"categoryId\":" + category);
        assertNull(versionCategory(id, 1));

        restore(id, 1, bearer, "200");

        assertCategory(id, bearer, null);
        assertEquals(title, jdbcTemplate.queryForObject("SELECT title FROM article WHERE id = ?", String.class, id));
        assertEquals(category, versionCategory(id, 2));
        assertEquals(publication, jdbcTemplate.queryForMap(
                "SELECT status, publish_time, author_id, visibility FROM article WHERE id = ?", id));
    }

    @Test
    void restoresCategorizedVersionAndRecordsPreviousCategory() throws Exception {
        String author = unique("it_category_restore_value_");
        long authorId = seedUser(author, "分类作者", "AUTHOR");
        String bearer = bearerOf(author);
        long categoryA = seedCategory(unique("历史分类"));
        long categoryB = seedCategory(unique("当前分类"));
        long id = categorizedArticle(authorId, categoryA);
        save(id, bearer, ",\"categoryId\":" + categoryB);

        restore(id, 1, bearer, "200");

        assertCategory(id, bearer, categoryA);
        assertEquals(categoryB, versionCategory(id, 2));
    }

    @Test
    void rejectsOtherAuthorsBeforeClearingCategoryOrCreatingSnapshots() throws Exception {
        String author = unique("it_category_owner_");
        long authorId = seedUser(author, "分类作者", "AUTHOR");
        String other = unique("it_category_other_");
        seedUser(other, "其他作者", "AUTHOR");
        String ownerBearer = bearerOf(author);
        String otherBearer = bearerOf(other);
        long category = seedCategory(unique("受保护分类"));
        long id = categorizedArticle(authorId, category);
        save(id, ownerBearer, ",\"categoryId\":" + category);

        mockMvc.perform(put("/articles/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, otherBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody(",\"categoryId\":null")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40301"));
        restore(id, 1, otherBearer, "40301");

        assertCategory(id, ownerBearer, category);
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_version WHERE article_id = ?", Integer.class, id));
    }

    @Test
    void partialMapperUpdateKeepsCategoryAndPasswordClearCanAlsoClearCategory() throws Exception {
        String author = unique("it_category_partial_");
        long authorId = seedUser(author, "分类作者", "AUTHOR");
        String bearer = bearerOf(author);
        long category = seedCategory(unique("保留分类"));
        long id = categorizedArticle(authorId, category);
        Article partial = new Article();
        partial.setId(id);
        partial.setIsTop((short) 1);
        articleMapper.updateById(partial);
        assertCategory(id, bearer, category);
        jdbcTemplate.update("UPDATE article SET visibility = 'PASSWORD', access_password_hash = ? WHERE id = ?",
                "$2a$existing-password-hash", id);

        save(id, bearer, ",\"categoryId\":null,\"visibility\":\"PUBLIC\"");

        assertCategory(id, bearer, null);
        assertNull(jdbcTemplate.queryForObject("SELECT access_password_hash FROM article WHERE id = ?", String.class, id));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT is_top FROM article WHERE id = ?", Integer.class, id));
    }

    private long categorizedArticle(long authorId, long category) {
        long id = seedArticle(authorId, unique("分类文章"), "PUBLISHED");
        jdbcTemplate.update("UPDATE article SET category_id = ? WHERE id = ?", category, id);
        return id;
    }

    private String saveBody(String categoryField) {
        return "{\"title\":\"保存后的标题\",\"content\":\"保存后的正文\",\"editorType\":\"MARKDOWN\"" + categoryField + "}";
    }

    private void save(long id, String bearer, String categoryField) throws Exception {
        mockMvc.perform(put("/articles/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody(categoryField)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
    }

    private void restore(long id, int version, String bearer, String code) throws Exception {
        mockMvc.perform(post("/articles/{id}/restore/{version}", id, version)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code));
    }

    private Long versionCategory(long id, int version) {
        return jdbcTemplate.queryForObject("SELECT category_id FROM article_version WHERE article_id = ? AND version = ?",
                Long.class, id, version);
    }

    private void assertCategory(long id, String bearer, Long expected) throws Exception {
        assertEquals(expected, jdbcTemplate.queryForObject("SELECT category_id FROM article WHERE id = ?", Long.class, id));
        var response = mockMvc.perform(get("/articles/{id}", id).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
        if (expected == null) {
            response.andExpect(jsonPath("$.data.categoryId").value(nullValue()))
                    .andExpect(jsonPath("$.data.categoryName").value(nullValue()));
        } else {
            response.andExpect(jsonPath("$.data.categoryId").value(expected));
        }
    }
}
