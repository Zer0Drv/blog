package com.zer0drv.blog.it;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * article 系 P0 接口级集成测试：版本历史、定时发布可见性、回收站（删除→TRASH→恢复→force）、
 * 搜索 LIKE 兜底（测试 yaml 已配 fulltext-enabled=false）、归档分组、自动保存权限路径。
 * Redis 深桩读恒 null，autosave 只测权限 / 参数 / 响应形态，存储行为由单测覆盖。
 *
 * @author Yoruhaki
 */
class ArticleP0IntegrationTests extends IntegrationTestSupport {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /**
     * 以 author 身份发布文章并返回 id
     */
    private long publishArticle(String bearer, String title) throws Exception {
        return publishArticle(bearer, title, null);
    }

    private long publishArticle(String bearer, String title, LocalDateTime publishTime) throws Exception {
        String schedule = publishTime == null ? ""
                : ",\"publishTime\":\"" + ISO.format(publishTime) + "\"";
        MvcResult created = mockMvc.perform(post("/articles")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","content":"%s 正文","editorType":"MARKDOWN","status":"PUBLISHED"%s}"""
                                .formatted(title, title, schedule)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).path("data").asLong();
    }

    // ---------- 版本历史 ----------

    @Test
    void versionHistorySnapshotDetailAndRestore() throws Exception {
        String author = unique("it_ver_author_");
        seedUser(author, "版本作者", "AUTHOR");
        String bearer = bearerOf(author);
        long articleId = publishArticle(bearer, unique("版本文章"));

        // 编辑一次：产 1 号快照（更新前的旧行）
        mockMvc.perform(put("/articles/{id}", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"改版标题\",\"content\":\"改版正文\",\"editorType\":\"MARKDOWN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        // 版本列表：version 倒序、不含 content
        mockMvc.perform(get("/articles/{id}/versions", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].version").value(1))
                .andExpect(jsonPath("$.data[0].content").doesNotExist());

        // 版本详情：含完整快照字段（旧行内容）
        mockMvc.perform(get("/articles/{id}/versions/{version}", articleId, 1)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isNotEmpty())
                .andExpect(jsonPath("$.data.editorType").value("MARKDOWN"));

        // 不存在的版本
        mockMvc.perform(get("/articles/{id}/versions/{version}", articleId, 99)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40067"));

        // 恢复到 1 号版本：标题回到旧值，且恢复本身留痕（版本数 1 → 2）
        mockMvc.perform(post("/articles/{id}/restore/{version}", articleId, 1)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
        mockMvc.perform(get("/articles/{id}", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.data.title").value(org.hamcrest.Matchers.not("改版标题")));
        mockMvc.perform(get("/articles/{id}/versions", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].version").value(2));

        // 其他作者无权看版本
        String other = unique("it_ver_other_");
        seedUser(other, "其他作者", "AUTHOR");
        mockMvc.perform(get("/articles/{id}/versions", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(other)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40301"));
        // 匿名：GET /articles/** 为 permitAll 路径，@PreAuthorize 拒绝走 GlobalExceptionHandler（200 + 40300）
        mockMvc.perform(get("/articles/{id}/versions", articleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40300"));
    }

    // ---------- 定时发布 ----------

    @Test
    void scheduledArticleHiddenUntilPublishTimeArrives() throws Exception {
        String author = unique("it_sch_author_");
        long authorId = seedUser(author, "定时作者", "AUTHOR");
        String bearer = bearerOf(author);
        String title = unique("定时文章");
        long articleId = publishArticle(bearer, title, LocalDateTime.now().plusHours(1));

        // 匿名详情：按不存在处理（不暴露存在性）
        mockMvc.perform(get("/articles/{id}", articleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40030"));
        // 作者本人可见
        mockMvc.perform(get("/articles/{id}", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
        // 公开列表 / 搜索 / 归档均不出现
        mockMvc.perform(get("/articles").param("keyword", title))
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/articles/search").param("keyword", title))
                .andExpect(jsonPath("$.data.total").value(0));
        assertArchivesAbsent(articleId);
        // 关注者 feed 同样不出现
        String fan = unique("it_sch_fan_");
        long fanId = seedUser(fan, "粉丝", "USER");
        jdbcTemplate.update("INSERT INTO follow (follower_id, followee_id) VALUES (?,?)", fanId, authorId);
        MvcResult feed = mockMvc.perform(get("/feed")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(fan)))
                .andExpect(status().isOk())
                .andReturn();
        if (objectMapper.readTree(feed.getResponse().getContentAsString())
                .path("data").path("records").toString().contains("\"id\":" + articleId + ",")) {
            throw new IllegalStateException("定时中文章不应出现在关注 Feed");
        }

        // 到期（直接把 publish_time 拨到过去）：匿名可见且浏览量自增
        jdbcTemplate.update("UPDATE article SET publish_time = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)), articleId);
        mockMvc.perform(get("/articles/{id}", articleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.viewCount").value(1));
        mockMvc.perform(get("/articles/search").param("keyword", title))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void schedulePublishTimeTooSoonRejected() throws Exception {
        String author = unique("it_sch_bad_");
        seedUser(author, "定时作者", "AUTHOR");
        String body = """
                {"title":"%s","content":"正文","editorType":"MARKDOWN","status":"PUBLISHED","publishTime":"%s"}"""
                .formatted(unique("定时过近"), ISO.format(LocalDateTime.now().plusSeconds(10)));
        mockMvc.perform(post("/articles")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40023"))
                .andExpect(jsonPath("$.message").value("定时发布时间必须晚于当前时间"));
    }

    // ---------- 回收站 ----------

    @Test
    void trashRestoreAndForceDeleteFlow() throws Exception {
        String author = unique("it_trash_author_");
        seedUser(author, "回收站作者", "AUTHOR");
        String bearer = bearerOf(author);
        long articleId = publishArticle(bearer, unique("回收站文章"));

        // 删除 → 进回收站（mine 默认列表不可见，TRASH 可见）
        mockMvc.perform(delete("/articles/{id}", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.code").value("200"));
        mockMvc.perform(get("/articles/mine")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/articles/mine").param("status", "TRASH")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(articleId));

        // 管理端全用户回收站可见（keyword 过滤保留）
        String admin = unique("it_trash_admin_");
        seedUser(admin, "管理员", "ADMIN");
        mockMvc.perform(get("/admin/articles").param("status", "TRASH").param("keyword", "回收站文章")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(admin)))
                .andExpect(jsonPath("$.data.total").value(1));

        // 恢复 → 回草稿态（不直接上线，匿名详情不可见）
        mockMvc.perform(post("/articles/{id}/restore", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.code").value("200"));
        mockMvc.perform(get("/articles/mine").param("status", "DRAFT")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/articles/{id}", articleId))
                .andExpect(jsonPath("$.code").value("40030"));

        // 重复恢复（已不在回收站）→ ARTICLE_NOT_EXIST
        mockMvc.perform(post("/articles/{id}/restore", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.code").value("40030"));

        // 再删 → 彻底删除：物理删行，回收站也查不到
        mockMvc.perform(delete("/articles/{id}", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.code").value("200"));
        mockMvc.perform(delete("/articles/{id}/force", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.code").value("200"));
        mockMvc.perform(get("/articles/mine").param("status", "TRASH")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.data.total").value(0));
        Long rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article WHERE id = ?", Long.class, articleId);
        if (rows == null || rows != 0) {
            throw new IllegalStateException("彻底删除后文章行仍存在");
        }
        // 再 force → 不存在
        mockMvc.perform(delete("/articles/{id}/force", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.code").value("40030"));
    }

    @Test
    void trashOperationsRejectNonAuthor() throws Exception {
        String author = unique("it_trash_owner_");
        seedUser(author, "作者", "AUTHOR");
        long articleId = publishArticle(bearerOf(author), unique("越权文章"));
        String other = unique("it_trash_other_");
        seedUser(other, "其他作者", "AUTHOR");
        String otherBearer = bearerOf(other);

        mockMvc.perform(delete("/articles/{id}", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(author)));
        mockMvc.perform(post("/articles/{id}/restore", articleId)
                        .header(HttpHeaders.AUTHORIZATION, otherBearer))
                .andExpect(jsonPath("$.code").value("40301"));
        mockMvc.perform(delete("/articles/{id}/force", articleId)
                        .header(HttpHeaders.AUTHORIZATION, otherBearer))
                .andExpect(jsonPath("$.code").value("40301"));
        // ADMIN 可代为彻底删除
        String admin = unique("it_trash_admin2_");
        seedUser(admin, "管理员", "ADMIN");
        mockMvc.perform(delete("/articles/{id}/force", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(admin)))
                .andExpect(jsonPath("$.code").value("200"));
    }

    // ---------- 搜索（LIKE 兜底路径） ----------

    @Test
    void searchFallbackMatchesTitleSummaryAndContent() throws Exception {
        String author = unique("it_search_author_");
        seedUser(author, "搜索作者", "AUTHOR");
        String bearer = bearerOf(author);
        String keyword = unique("搜索关键词");
        // 标题命中
        publishArticle(bearer, keyword + "标题篇");
        // 仅正文命中（content_text 三路 LIKE）
        long contentHit = publishArticle(bearer, unique("普通标题"));
        jdbcTemplate.update("UPDATE article SET content = ?, content_text = ? WHERE id = ?",
                "正文含 " + keyword, "正文含 " + keyword, contentHit);

        mockMvc.perform(get("/articles/search").param("keyword", keyword))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.total").value(2));

        // 关键字空白 → PARAM_INVALID
        mockMvc.perform(get("/articles/search").param("keyword", "  "))
                .andExpect(jsonPath("$.code").value("40023"));
        mockMvc.perform(get("/articles/search"))
                .andExpect(jsonPath("$.code").value("40023"));
    }

    // ---------- 归档 ----------

    @Test
    @SuppressWarnings("unchecked")
    void archivesGroupByMonthDescending() throws Exception {
        String author = unique("it_arc_author_");
        seedUser(author, "归档作者", "AUTHOR");
        String bearer = bearerOf(author);
        // 用一个遥远月份避免与其他 IT 数据混淆
        long a1 = publishArticle(bearer, unique("归档甲"));
        long a2 = publishArticle(bearer, unique("归档乙"));
        long a3 = publishArticle(bearer, unique("归档丙"));
        jdbcTemplate.update("UPDATE article SET publish_time = ? WHERE id = ?",
                Timestamp.valueOf("2001-03-15 10:00:00"), a1);
        jdbcTemplate.update("UPDATE article SET publish_time = ? WHERE id = ?",
                Timestamp.valueOf("2001-03-20 10:00:00"), a2);
        jdbcTemplate.update("UPDATE article SET publish_time = ? WHERE id = ?",
                Timestamp.valueOf("2001-02-10 10:00:00"), a3);

        MvcResult result = mockMvc.perform(get("/articles/archives"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andReturn();
        List<Map<String, Object>> months = objectMapper.convertValue(
                objectMapper.readTree(result.getResponse().getContentAsString()).path("data"),
                new tools.jackson.core.type.TypeReference<>() {
                });

        int idxMar = indexOfMonth(months, "2001-03");
        int idxFeb = indexOfMonth(months, "2001-02");
        if (idxMar < 0 || idxFeb < 0) {
            throw new IllegalStateException("归档缺少预置月份");
        }
        // 月份倒序：2001-03 在 2001-02 之前
        if (idxMar >= idxFeb) {
            throw new IllegalStateException("归档月份未按倒序排列");
        }
        Map<String, Object> mar = months.get(idxMar);
        if (!Integer.valueOf(2).equals((Integer) mar.get("count"))) {
            throw new IllegalStateException("2001-03 归档计数应为 2");
        }
        List<Map<String, Object>> items = (List<Map<String, Object>>) mar.get("articles");
        // 月内按 publishTime 倒序：a2(03-20) 在 a1(03-15) 前
        Number firstId = (Number) items.get(0).get("id");
        if (firstId.longValue() != a2) {
            throw new IllegalStateException("月内文章未按发布时间倒序");
        }
    }

    private int indexOfMonth(List<Map<String, Object>> months, String month) {
        for (int i = 0; i < months.size(); i++) {
            if (month.equals(months.get(i).get("month"))) {
                return i;
            }
        }
        return -1;
    }

    private void assertArchivesAbsent(long articleId) throws Exception {
        MvcResult result = mockMvc.perform(get("/articles/archives"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        if (body.contains("\"id\":" + articleId + ",")) {
            throw new IllegalStateException("定时中文章不应出现在归档");
        }
    }

    // ---------- 自动保存（Redis 深桩，只测权限 / 参数 / 响应形态） ----------

    @Test
    void autosavePermissionAndValidation() throws Exception {
        String author = unique("it_auto_author_");
        seedUser(author, "作者", "AUTHOR");
        String bearer = bearerOf(author);
        long articleId = publishArticle(bearer, unique("自动保存文章"));

        // 作者保存草稿：返回 savedAt
        mockMvc.perform(put("/articles/{id}/autosave", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"草稿\",\"content\":\"草稿正文\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.savedAt").isNumber());
        // Redis 深桩读恒 null → exists:false
        mockMvc.perform(get("/articles/{id}/autosave", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.data.exists").value(false));
        // 正文为空 → 参数校验
        mockMvc.perform(put("/articles/{id}/autosave", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"草稿\"}"))
                .andExpect(jsonPath("$.code").value("40023"));
        // 非作者 → 40301；匿名 → 401
        String other = unique("it_auto_other_");
        seedUser(other, "其他作者", "AUTHOR");
        mockMvc.perform(put("/articles/{id}/autosave", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"x\"}"))
                .andExpect(jsonPath("$.code").value("40301"));
        mockMvc.perform(get("/articles/{id}/autosave", articleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40300"));
    }
}
