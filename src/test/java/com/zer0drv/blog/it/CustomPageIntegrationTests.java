package com.zer0drv.blog.it;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CustomPageIntegrationTests extends IntegrationTestSupport {
    private String admin() throws Exception {
        String name = unique("page_admin_");
        seedUser(name, "页面管理员", "ADMIN");
        return bearerOf(name);
    }

    private String body(String slug, String status, String editor, String content) {
        return objectMapper.writeValueAsString(Map.of("slug", slug, "title", "页面 " + slug,
                "content", content, "editorType", editor, "status", status));
    }

    private long create(String bearer, String slug, String status) throws Exception {
        var result = mockMvc.perform(post("/admin/pages").header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body(slug, status, "MARKDOWN", "# 页面正文")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("200")).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").asLong();
    }

    @Test
    void managementIsAdminOnly() throws Exception {
        mockMvc.perform(get("/admin/pages")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/admin/pages").contentType(MediaType.APPLICATION_JSON)
                .content(body("about", "DRAFT", "MARKDOWN", "正文"))).andExpect(status().isUnauthorized());
        for (String role : new String[]{"USER", "AUTHOR"}) {
            String name = unique("page_nonadmin_");
            seedUser(name, "非管理员", role);
            String bearer = bearerOf(name);
            mockMvc.perform(get("/admin/pages").header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isForbidden());
            mockMvc.perform(post("/admin/pages").header(HttpHeaders.AUTHORIZATION, bearer)
                    .contentType(MediaType.APPLICATION_JSON).content(body("about", "DRAFT", "MARKDOWN", "正文")))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void invalidPaginationCannotBypassResultLimit() throws Exception {
        String bearer = admin();
        for (String size : new String[]{"0", "-1", "501"}) {
            mockMvc.perform(get("/admin/pages").header(HttpHeaders.AUTHORIZATION, bearer).param("size", size))
                    .andExpect(jsonPath("$.code").value("40023"));
        }
        mockMvc.perform(get("/admin/pages").header(HttpHeaders.AUTHORIZATION, bearer).param("page", "0"))
                .andExpect(jsonPath("$.code").value("40023"));
    }

    @Test
    void draftPublishOfflineDelete_publicAndSitemapStayConsistent() throws Exception {
        String bearer = admin();
        String slug = unique("page-lifecycle-");
        long id = create(bearer, slug, "DRAFT");
        mockMvc.perform(get("/pages/{slug}", slug)).andExpect(jsonPath("$.code").value("40079"));
        mockMvc.perform(get("/admin/pages/{id}", id).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.data.content").value("# 页面正文"));
        mockMvc.perform(get("/admin/pages").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.data.records[?(@.id == " + id + ")].content")
                        .value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())));
        for (String state : new String[]{"PUBLISHED", "OFFLINE"}) {
            mockMvc.perform(put("/admin/pages/{id}", id).header(HttpHeaders.AUTHORIZATION, bearer)
                    .contentType(MediaType.APPLICATION_JSON).content(body(slug, state, "MARKDOWN", "# 页面正文")))
                    .andExpect(jsonPath("$.code").value("200"));
            mockMvc.perform(get("/pages/{slug}", slug))
                    .andExpect(jsonPath("$.code").value("PUBLISHED".equals(state) ? "200" : "40079"));
            String sitemap = mockMvc.perform(get("/sitemap.xml")).andReturn().getResponse().getContentAsString();
            assertEquals("PUBLISHED".equals(state), sitemap.contains("/pages/" + slug + "</loc>"));
            String rss = mockMvc.perform(get("/rss.xml")).andReturn().getResponse().getContentAsString();
            assertFalse(rss.contains(slug));
        }
        mockMvc.perform(delete("/admin/pages/{id}", id).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.code").value("200"));
        mockMvc.perform(get("/admin/pages/{id}", id).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.code").value("40079"));
        mockMvc.perform(post("/admin/pages").header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body(slug, "PUBLISHED", "MARKDOWN", "正文")))
                .andExpect(jsonPath("$.code").value("40080"));
    }

    @Test
    void navigationPreservesOrderAndHidesUnpublishedOrDeletedPages() throws Exception {
        String bearer = admin();
        String first = unique("nav-first-");
        String second = unique("nav-second-");
        String draft = unique("nav-draft-");
        long firstId = create(bearer, first, "PUBLISHED");
        long secondId = create(bearer, second, "PUBLISHED");
        create(bearer, draft, "DRAFT");
        try {
            mockMvc.perform(put("/admin/site/config").header(HttpHeaders.AUTHORIZATION, bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("site.page_navigation", "[\"" + second + "\",\"" + draft + "\",\"" + first + "\"]"))))
                    .andExpect(jsonPath("$.code").value("200"));
            mockMvc.perform(get("/site/page-navigation"))
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].slug").value(second))
                    .andExpect(jsonPath("$.data[1].slug").value(first));
            mockMvc.perform(get("/site/config")).andExpect(jsonPath("$.data['site.page_navigation']").doesNotExist());
            mockMvc.perform(delete("/admin/pages/{id}", secondId).header(HttpHeaders.AUTHORIZATION, bearer));
            mockMvc.perform(get("/site/page-navigation")).andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(firstId));
        } finally {
            mockMvc.perform(put("/admin/site/config").header(HttpHeaders.AUTHORIZATION, bearer)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"site.page_navigation\":\"[]\"}"));
        }
    }

    @Test
    void invalidNavigationRejectsBeforeAnyConfigWrite() throws Exception {
        String bearer = admin();
        for (String invalid : new String[]{"{}", "[\"javascript:alert(1)\"]", "[\"about\",\"about\"]", "[12]"}) {
            mockMvc.perform(put("/admin/site/config").header(HttpHeaders.AUTHORIZATION, bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("site.name", "should-not-save", "site.page_navigation", invalid))))
                    .andExpect(jsonPath("$.code").value("40023"));
        }
        assertNotEquals("should-not-save", jdbcTemplate.queryForObject(
                "SELECT config_value FROM site_config WHERE config_key = 'site.name'", String.class));
    }

    @Test
    void slugAndStatusValidation_uniqueSlugsAndContentSanitization() throws Exception {
        String bearer = admin();
        String slug = unique("page-safe-");
        for (String invalidSlug : new String[]{"Bad_Slug", "../about", "about--us"}) {
            mockMvc.perform(post("/admin/pages").header(HttpHeaders.AUTHORIZATION, bearer)
                    .contentType(MediaType.APPLICATION_JSON).content(body(invalidSlug, "DRAFT", "MARKDOWN", "正文")))
                    .andExpect(jsonPath("$.code").value("40023"));
        }
        mockMvc.perform(post("/admin/pages").header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body(slug, "PRIVATE", "MARKDOWN", "正文")))
                .andExpect(jsonPath("$.code").value("40023"));
        long id = create(bearer, slug, "DRAFT");
        mockMvc.perform(post("/admin/pages").header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body(slug, "DRAFT", "MARKDOWN", "正文")))
                .andExpect(jsonPath("$.code").value("40080"));
        mockMvc.perform(put("/admin/pages/{id}", id).header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(slug, "PUBLISHED", "RICHTEXT", "<p>safe</p><script>alert(1)</script><img src=\"https://example.com/a.png\" onerror=\"alert(1)\">")))
                .andExpect(jsonPath("$.code").value("200"));
        String content = objectMapper.readTree(mockMvc.perform(get("/pages/{slug}", slug))
                .andReturn().getResponse().getContentAsString()).path("data").path("content").asString();
        assertTrue(content.contains("<p>safe</p>"));
        assertFalse(content.contains("script"));
        assertFalse(content.contains("onerror"));
    }
}
