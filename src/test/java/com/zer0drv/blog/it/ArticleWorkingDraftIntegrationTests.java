package com.zer0drv.blog.it;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** Fast HTTP/database coverage of the durable editor and optimistic conflict contract. */
class ArticleWorkingDraftIntegrationTests extends IntegrationTestSupport {
    private record Fixture(long id, String bearer, String title) { }

    private Fixture article(String state) throws Exception {
        String name = unique("work_author_");
        long author = seedUser(name, "工作稿作者", "AUTHOR");
        String title = unique("生效标题");
        return new Fixture(seedArticle(author, title, state), bearerOf(name), title);
    }

    private String content(long revision, String title, String text) {
        return objectMapper.writeValueAsString(Map.of("editRevision", revision, "title", title,
                "content", text, "editorType", "MARKDOWN", "status", "PUBLISHED"));
    }

    @Test
    void durableWorkingCopyRequiresExplicitPublishAndRejectsStaleWrites() throws Exception {
        var f = article("PUBLISHED");
        var original = jdbcTemplate.queryForMap("SELECT title,content,publish_time,update_time FROM article WHERE id=?", f.id());
        mockMvc.perform(put("/articles/{id}", f.id()).header(HttpHeaders.AUTHORIZATION, f.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(content(0, "待发布标题", "独立工作稿正文")))
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.editRevision").value(1))
                .andExpect(jsonPath("$.data.hasWorkingCopy").value(true));
        assertEquals(original, jdbcTemplate.queryForMap("SELECT title,content,publish_time,update_time FROM article WHERE id=?", f.id()));
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM article_version WHERE article_id=?", Long.class, f.id()));
        mockMvc.perform(get("/articles/{id}/editing", f.id()).header(HttpHeaders.AUTHORIZATION, f.bearer()))
                .andExpect(jsonPath("$.data.title").value("待发布标题"))
                .andExpect(jsonPath("$.data.activeTitle").value(f.title()));
        mockMvc.perform(get("/articles/{id}", f.id()))
                .andExpect(jsonPath("$.data.title").value(f.title()))
                .andExpect(jsonPath("$.data.hasWorkingCopy").doesNotExist());
        mockMvc.perform(post("/articles/{id}/publish", f.id()).header(HttpHeaders.AUTHORIZATION, f.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(content(0, "过期标题", "过期正文")))
                .andExpect(jsonPath("$.code").value("40901"));
        assertEquals("独立工作稿正文", jdbcTemplate.queryForObject("SELECT content FROM article_working_copy WHERE article_id=?", String.class, f.id()));
        publishWorkingCopy(f.id(), f.bearer());
        mockMvc.perform(get("/articles/{id}", f.id())).andExpect(jsonPath("$.data.title").value("待发布标题"));
        assertEquals(f.title(), jdbcTemplate.queryForObject("SELECT title FROM article_version WHERE article_id=? AND version=1", String.class, f.id()));
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM article_working_copy WHERE article_id=?", Long.class, f.id()));
    }

    @Test
    void autosaveAcceptsEmptyContentAndDiscardAdvancesToken() throws Exception {
        var f = article("DRAFT");
        mockMvc.perform(put("/articles/{id}/autosave", f.id()).header(HttpHeaders.AUTHORIZATION, f.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(content(0, "", "")))
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.editRevision").value(1));
        mockMvc.perform(get("/articles/{id}/editing", f.id()).header(HttpHeaders.AUTHORIZATION, f.bearer()))
                .andExpect(jsonPath("$.data.title").value(""))
                .andExpect(jsonPath("$.data.content").value(""));
        assertEquals(f.title(), jdbcTemplate.queryForObject("SELECT title FROM article WHERE id=?", String.class, f.id()));
        mockMvc.perform(delete("/articles/{id}/working-copy", f.id()).param("editRevision", "1")
                .header(HttpHeaders.AUTHORIZATION, f.bearer()))
                .andExpect(jsonPath("$.data.editRevision").value(2))
                .andExpect(jsonPath("$.data.hasWorkingCopy").value(false))
                .andExpect(jsonPath("$.data.title").value(f.title()));
        mockMvc.perform(put("/articles/{id}/autosave", f.id()).header(HttpHeaders.AUTHORIZATION, f.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(content(1, "旧窗口", "草稿")))
                .andExpect(jsonPath("$.code").value("40901"));
    }

    @Test
    void missingRevisionAndUnauthorizedEditingAreRejected() throws Exception {
        var f = article("PUBLISHED");
        mockMvc.perform(put("/articles/{id}", f.id()).header(HttpHeaders.AUTHORIZATION, f.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"覆盖\",\"content\":\"正文\",\"editorType\":\"MARKDOWN\"}"))
                .andExpect(jsonPath("$.code").value("40023"));
        String other = unique("work_other_"); seedUser(other, "其他作者", "AUTHOR");
        mockMvc.perform(get("/articles/{id}/editing", f.id()).header(HttpHeaders.AUTHORIZATION, bearerOf(other)))
                .andExpect(jsonPath("$.code").value("40301"));
        mockMvc.perform(get("/articles/{id}/editing", f.id())).andExpect(jsonPath("$.code").value("40300"));
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT edit_revision FROM article WHERE id=?", Long.class, f.id()));
    }

    @Test
    void offlineManualSaveCannotPublishAndExplicitRepublishKeepsOriginalTime() throws Exception {
        var f = article("OFFLINE");
        var originalTime = Timestamp.valueOf(LocalDateTime.now().minusDays(2).withNano(0));
        jdbcTemplate.update("UPDATE article SET publish_time=? WHERE id=?", originalTime, f.id());
        mockMvc.perform(put("/articles/{id}", f.id()).header(HttpHeaders.AUTHORIZATION, f.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(content(0, "下架修改", "正文")))
                .andExpect(jsonPath("$.data.activeStatus").value("OFFLINE"));
        mockMvc.perform(post("/articles/{id}/publish", f.id()).header(HttpHeaders.AUTHORIZATION, f.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(content(1, "重新发布", "正文")))
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.activeStatus").value("PUBLISHED"));
        assertEquals(originalTime, jdbcTemplate.queryForObject("SELECT publish_time FROM article WHERE id=?", Timestamp.class, f.id()));
        mockMvc.perform(get("/articles/{id}", f.id())).andExpect(jsonPath("$.data.title").value("重新发布"));
    }
}
