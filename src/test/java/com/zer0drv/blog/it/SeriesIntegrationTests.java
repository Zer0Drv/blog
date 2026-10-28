package com.zer0drv.blog.it;

import com.zer0drv.blog.article.series.mapper.SeriesRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SeriesIntegrationTests extends IntegrationTestSupport {
    @Autowired private SeriesRepository repository;

    private record Actor(long id, String auth) { }

    private Actor actor(String role) throws Exception {
        String username = unique("series_user_");
        long id = seedUser(username, "系列作者", role);
        return new Actor(id, bearerOf(username));
    }

    private Map<String, Object> body(String slug, String status, List<Long> ids, Long revision) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "系列 " + slug);
        body.put("description", "有序章节");
        body.put("cover", "");
        body.put("slug", slug);
        body.put("status", status);
        body.put("articleIds", ids);
        if (revision != null) body.put("revision", revision);
        return body;
    }

    private JsonNode create(Actor actor, String slug, String status, List<Long> ids) throws Exception {
        var result = mockMvc.perform(post("/series/manage").header(HttpHeaders.AUTHORIZATION, actor.auth())
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body(slug, status, ids, null))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("200")).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private void update(Actor actor, long id, Map<String, Object> body, String code) throws Exception {
        mockMvc.perform(put("/series/manage/{id}", id).header(HttpHeaders.AUTHORIZATION, actor.auth())
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(code));
    }

    @Test
    void visibilityOrderNavigationAndMembershipAlwaysUseCurrentPublicArticles() throws Exception {
        Actor owner = actor("AUTHOR");
        long first = seedArticle(owner.id(), unique("series_first_"), "PUBLISHED");
        long hidden = seedArticle(owner.id(), unique("series_hidden_"), "DRAFT");
        long last = seedArticle(owner.id(), unique("series_last_"), "PUBLISHED");
        String slug = unique("series-visible-");
        long id = create(owner, slug, "PUBLISHED", List.of(first, hidden, last)).path("id").asLong();
        mockMvc.perform(get("/series/{slug}", slug)).andExpect(jsonPath("$.data.articleCount").value(2))
                .andExpect(jsonPath("$.data.articles[0].id").value(first))
                .andExpect(jsonPath("$.data.articles[1].id").value(last))
                .andExpect(jsonPath("$.data.articles[0].position").doesNotExist())
                .andExpect(jsonPath("$.data.articles[0].status").doesNotExist())
                .andExpect(jsonPath("$.data.revision").doesNotExist());
        mockMvc.perform(get("/series/by-article/{id}", first)).andExpect(jsonPath("$.data[0].next.id").value(last))
                .andExpect(jsonPath("$.data[0].previous").isEmpty());
        mockMvc.perform(get("/series/by-article/{id}", hidden)).andExpect(jsonPath("$.data").isEmpty());
        for (String visibility : List.of("PRIVATE", "PASSWORD")) {
            jdbcTemplate.update("UPDATE article SET visibility=? WHERE id=?", visibility, last);
            mockMvc.perform(get("/series/{slug}", slug)).andExpect(jsonPath("$.data.articleCount").value(1));
            mockMvc.perform(get("/series/by-article/{id}", first)).andExpect(jsonPath("$.data[0].next").isEmpty());
            mockMvc.perform(get("/series/by-article/{id}", last)).andExpect(jsonPath("$.data").isEmpty());
        }
        mockMvc.perform(get("/series/manage/{id}", id).header(HttpHeaders.AUTHORIZATION, owner.auth()))
                .andExpect(jsonPath("$.data.articles.length()").value(3))
                .andExpect(jsonPath("$.data.articles[2].visible").value(false));
        create(owner, unique("series-second-"), "PUBLISHED", List.of(first));
        mockMvc.perform(get("/series/by-article/{id}", first)).andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void managementRequiresAuthorAndOnlyOwnersOrAdminsCanReadOrChange() throws Exception {
        Actor owner = actor("AUTHOR");
        Actor other = actor("AUTHOR");
        Actor reader = actor("USER");
        Actor admin = actor("SUPER_ADMIN");
        long own = seedArticle(owner.id(), unique("series_own_"), "DRAFT");
        long foreign = seedArticle(other.id(), unique("series_foreign_"), "DRAFT");
        String slug = unique("series-access-");
        long id = create(owner, slug, "DRAFT", List.of(own)).path("id").asLong();
        mockMvc.perform(get("/series/manage")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/series/manage/candidates").header(HttpHeaders.AUTHORIZATION, reader.auth()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/series/manage/{id}", id).header(HttpHeaders.AUTHORIZATION, other.auth()))
                .andExpect(jsonPath("$.code").value("40301"));
        mockMvc.perform(get("/series/manage").param("all", "true").header(HttpHeaders.AUTHORIZATION, owner.auth()))
                .andExpect(jsonPath("$.code").value("40301"));
        mockMvc.perform(get("/series/manage/{id}", id).header(HttpHeaders.AUTHORIZATION, admin.auth()))
                .andExpect(jsonPath("$.data.ownerId").value(owner.id()));
        mockMvc.perform(get("/series/manage/candidates").param("seriesId", Long.toString(id))
                        .header(HttpHeaders.AUTHORIZATION, admin.auth()))
                .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.records[0].id").value(own));
        update(admin, id, body(slug, "DRAFT", List.of(foreign), 0L), "40023");
        update(other, id, body(slug, "DRAFT", List.of(foreign), 0L), "40301");
        update(admin, id, body(slug, "PUBLISHED", List.of(own), 0L), "200");
        mockMvc.perform(get("/series/manage/{id}", id).header(HttpHeaders.AUTHORIZATION, owner.auth()))
                .andExpect(jsonPath("$.data.ownerId").value(owner.id()));
    }

    @Test
    void replacementRejectsStaleRevisionAndInvalidChaptersWithoutLosingOrder() throws Exception {
        Actor owner = actor("AUTHOR");
        long a = seedArticle(owner.id(), unique("series_a_"), "PUBLISHED");
        long b = seedArticle(owner.id(), unique("series_b_"), "PUBLISHED");
        String slug = unique("series-order-");
        long id = create(owner, slug, "PUBLISHED", List.of(a, b)).path("id").asLong();
        update(owner, id, body(slug, "PUBLISHED", List.of(b, a), 0L), "200");
        update(owner, id, body(slug, "DRAFT", List.of(a), 0L), "40900");
        update(owner, id, body(slug, "PUBLISHED", List.of(a, a), 1L), "40023");
        update(owner, id, body(slug, "PUBLISHED", List.of(Long.MAX_VALUE), 1L), "40023");
        update(owner, id, body(slug, "PUBLISHED", List.of(a), null), "40023");
        mockMvc.perform(delete("/series/manage/{id}", id).param("revision", "0").header(HttpHeaders.AUTHORIZATION, owner.auth()))
                .andExpect(jsonPath("$.code").value("40900"));
        mockMvc.perform(get("/series/{slug}", slug)).andExpect(jsonPath("$.data.articles[0].id").value(b))
                .andExpect(jsonPath("$.data.articles[1].id").value(a));
        assertEquals(1L, repository.find(id).revision());
        update(owner, id, body(slug, "PUBLISHED", List.of(a), 1L), "200");
        assertEquals(List.of(a), repository.managedChapters(id).stream().map(c -> c.id()).toList());
    }

    @Test
    void paginationSlugAndPayloadBoundariesAreValidated() throws Exception {
        Actor owner = actor("AUTHOR");
        for (String size : List.of("0", "-1", "101", "9223372036854775807")) {
            mockMvc.perform(get("/series").param("size", size)).andExpect(jsonPath("$.code").value("40023"));
        }
        for (String page : List.of("0", "-1", "1000001", "9223372036854775807")) {
            mockMvc.perform(get("/series").param("page", page)).andExpect(jsonPath("$.code").value("40023"));
        }
        for (String slug : List.of("manage", "by-article", "Upper", "bad--slug", "-bad", "a".repeat(201))) {
            mockMvc.perform(post("/series/manage").header(HttpHeaders.AUTHORIZATION, owner.auth())
                            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body(slug, "DRAFT", List.of(), null))))
                    .andExpect(jsonPath("$.code").value("40023"));
        }
        Map<String, Object> invalid = body(unique("series-invalid-"), "DRAFT", List.of(), null);
        for (Map.Entry<String, Object> entry : Map.<String, Object>of("title", " ", "description", "a".repeat(2001),
                "cover", "a".repeat(513), "status", "PUBLIC", "revision", -1).entrySet()) {
            var copy = new LinkedHashMap<>(invalid);
            copy.put(entry.getKey(), entry.getValue());
            mockMvc.perform(post("/series/manage").header(HttpHeaders.AUTHORIZATION, owner.auth())
                            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(copy)))
                    .andExpect(jsonPath("$.code").value("40023"));
        }
        var tooMany = new ArrayList<Long>();
        for (long number = 1; number <= 201; number++) tooMany.add(number);
        invalid.put("articleIds", tooMany);
        mockMvc.perform(post("/series/manage").header(HttpHeaders.AUTHORIZATION, owner.auth())
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(invalid)))
                .andExpect(jsonPath("$.code").value("40023"));
    }

    @Test
    void emptyPublishedSeriesAndDeletionKeepSitemapAndArticlesConsistent() throws Exception {
        Actor owner = actor("AUTHOR");
        String slug = unique("series-empty-");
        long id = create(owner, slug, "DRAFT", List.of()).path("id").asLong();
        mockMvc.perform(get("/series/{slug}", slug)).andExpect(jsonPath("$.code").value("40085"));
        update(owner, id, body(slug, "PUBLISHED", List.of(), 0L), "200");
        mockMvc.perform(get("/series/{slug}", slug)).andExpect(jsonPath("$.data.articleCount").value(0));
        assertTrue(mockMvc.perform(get("/sitemap.xml")).andReturn().getResponse().getContentAsString().contains("/series/" + slug));
        mockMvc.perform(post("/series/manage").header(HttpHeaders.AUTHORIZATION, owner.auth())
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body(slug, "DRAFT", List.of(), null))))
                .andExpect(jsonPath("$.code").value("40086"));
        mockMvc.perform(delete("/series/manage/{id}", id).param("revision", "1").header(HttpHeaders.AUTHORIZATION, owner.auth()))
                .andExpect(jsonPath("$.code").value("200"));
        mockMvc.perform(get("/series/{slug}", slug)).andExpect(jsonPath("$.code").value("40085"));
        assertFalse(mockMvc.perform(get("/sitemap.xml")).andReturn().getResponse().getContentAsString().contains("/series/" + slug));
        create(owner, slug, "PUBLISHED", List.of());
    }

    @Test
    void literalCandidateSearchAndArticlePhysicalDeletionCleanMembershipOnly() throws Exception {
        Actor owner = actor("AUTHOR");
        long article = seedArticle(owner.id(), unique("series_100%_"), "PUBLISHED");
        seedArticle(owner.id(), unique("series_plain_"), "PUBLISHED");
        String slug = unique("series-purge-");
        long id = create(owner, slug, "PUBLISHED", List.of(article)).path("id").asLong();
        mockMvc.perform(get("/series/manage/candidates").param("keyword", "%").header(HttpHeaders.AUTHORIZATION, owner.auth()))
                .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.records[0].id").value(article));
        mockMvc.perform(delete("/articles/{id}/force", article).header(HttpHeaders.AUTHORIZATION, owner.auth()))
                .andExpect(jsonPath("$.code").value("200"));
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM article_series_item WHERE series_id=?", Long.class, id));
        mockMvc.perform(get("/series/{slug}", slug)).andExpect(jsonPath("$.data.articleCount").value(0));
    }
}
