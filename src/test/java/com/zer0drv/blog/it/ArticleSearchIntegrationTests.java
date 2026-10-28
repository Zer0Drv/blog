package com.zer0drv.blog.it;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** Real HTTP mapping, H2 literal LIKE, public predicates and bound filter pagination. */
class ArticleSearchIntegrationTests extends IntegrationTestSupport {
    @Test void filtersDatesVisibilityAndStablePagesComposeBeforeCount() throws Exception {
        long category = category(), tag = tag();
        long first = article(category, "搜索正文", "2001-01-01 00:00:00", "PUBLISHED", "PUBLIC", 0);
        long second = article(category, "搜索正文", "2001-01-01 00:00:00", "PUBLISHED", "PUBLIC", 0);
        long last = article(category, "搜索正文", "2001-01-01 23:59:59", "PUBLISHED", "PUBLIC", 0);
        article(category, "搜索正文", "2001-01-02 00:00:00", "PUBLISHED", "PUBLIC", 0);
        article(category, "搜索正文", "2000-12-31 23:59:59", "PUBLISHED", "PUBLIC", 0);
        article(category, "搜索正文", "2001-01-01 12:00:00", "PUBLISHED", "PUBLIC", 0); // untagged
        article(category(), "搜索正文", "2001-01-01 12:00:00", "PUBLISHED", "PUBLIC", 0);
        for (String state : List.of("DRAFT", "OFFLINE"))
            tagged(article(category, "搜索隐藏", "2001-01-01 12:00:00", state, "PUBLIC", 0), tag);
        for (String visibility : List.of("PASSWORD", "PRIVATE"))
            tagged(article(category, "搜索隐藏", "2001-01-01 12:00:00", "PUBLISHED", visibility, 0), tag);
        tagged(article(category, "搜索隐藏", "2001-01-01 12:00:00", "PUBLISHED", "PUBLIC", 1), tag);
        tagged(article(category, "搜索隐藏", "2999-01-01 12:00:00", "PUBLISHED", "PUBLIC", 0), tag);
        for (long id : List.of(first, second, last)) tagged(id, tag);

        var newest = filtered(category, tag, "relevance", "1");
        assertEquals(3, newest.path("total").asInt());
        assertEquals("LIKE", newest.path("searchMode").asString());
        assertEquals("newest", newest.path("effectiveSort").asString());
        assertEquals(last, newest.path("records").get(0).path("id").asLong());
        assertEquals(second, filtered(category, tag, "newest", "2").path("records").get(0).path("id").asLong());
        assertEquals(first, filtered(category, tag, "newest", "3").path("records").get(0).path("id").asLong());
        assertEquals(first, filtered(category, tag, "oldest", "1").path("records").get(0).path("id").asLong());
        assertEquals("搜索正文", newest.path("records").get(0).path("snippet").asString());
        assertEquals("搜索", newest.path("records").get(0).path("highlightTerms").get(0).asString());
        assertEquals(1, newest.path("records").get(0).path("highlightTerms").size());
        assertFalse(newest.path("records").get(0).has("content"));
        assertFalse(newest.path("records").get(0).has("accessPasswordHash"));
    }

    @Test void specialCharactersAreLiteralAndBlankKeywordRestoresPublicResults() throws Exception {
        long category = category();
        for (String keyword : List.of("%", "_", "!", "\\", "中", "<img onerror=x>")) {
            article(category, "body " + keyword, "2001-01-01 00:00:00", "PUBLISHED", "PUBLIC", 0);
        }
        for (String keyword : List.of("%", "_", "!", "\\", "中", "<img onerror=x>")) {
            mockMvc.perform(get("/articles/search").param("keyword", keyword).param("categoryId", "" + category))
                    .andExpect(jsonPath("$.code").value("200"))
                    .andExpect(jsonPath("$.data.total").value(1));
        }
        mockMvc.perform(get("/articles/search").param("categoryId", "" + category))
                .andExpect(jsonPath("$.data.total").value(6));
        mockMvc.perform(get("/articles/search").param("keyword", "not-present").param("categoryId", "" + category))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test void invalidQueryInputsProduceClientErrorWithoutQuerying() throws Exception {
        for (String[] invalid : List.of(new String[]{"size", "0"}, new String[]{"size", "101"},
                new String[]{"page", "-1"}, new String[]{"sort", "score DESC"}, new String[]{"categoryId", "-1"},
                new String[]{"tagId", "abc"}, new String[]{"publishedFrom", "2024-02-30"},
                new String[]{"publishedTo", "9999-12-31"}, new String[]{"keyword", "x".repeat(201)})) {
            mockMvc.perform(get("/articles/search").param(invalid[0], invalid[1]))
                    .andExpect(jsonPath("$.code").value("40023"));
        }
        mockMvc.perform(get("/articles/search").param("publishedFrom", "2025-01-02").param("publishedTo", "2025-01-01"))
                .andExpect(jsonPath("$.code").value("40023"));
    }

    private JsonNode filtered(long category, long tag, String sort, String page) throws Exception {
        var result = mockMvc.perform(get("/articles/search").param("keyword", "搜索")
                        .param("categoryId", "" + category).param("tagId", "" + tag)
                        .param("publishedFrom", "2001-01-01").param("publishedTo", "2001-01-01")
                        .param("sort", sort).param("page", page).param("size", "1"))
                .andExpect(jsonPath("$.code").value("200")).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private long category() {
        String name = unique("search_category_");
        jdbcTemplate.update("INSERT INTO category(name) VALUES (?)", name);
        return jdbcTemplate.queryForObject("SELECT id FROM category WHERE name=?", Long.class, name);
    }

    private long tag() {
        String name = unique("search_tag_");
        jdbcTemplate.update("INSERT INTO tag(name) VALUES (?)", name);
        return jdbcTemplate.queryForObject("SELECT id FROM tag WHERE name=?", Long.class, name);
    }

    private long article(long category, String body, String time, String status, String visibility, int deleted) {
        String title = unique("searcharticle");
        jdbcTemplate.update("INSERT INTO article(title,content,content_text,editor_type,author_id,category_id,status,visibility,publish_time,deleted) VALUES(?,?,?,'MARKDOWN',1,?,?,?,?,?)",
                title, body, body, category, status, visibility, time, deleted);
        return jdbcTemplate.queryForObject("SELECT id FROM article WHERE title=?", Long.class, title);
    }

    private void tagged(long article, long tag) {
        jdbcTemplate.update("INSERT INTO article_tag(article_id,tag_id) VALUES(?,?)", article, tag);
    }
}
