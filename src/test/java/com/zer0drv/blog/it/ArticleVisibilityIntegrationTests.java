package com.zer0drv.blog.it;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 密码保护 / 私密文章接口级集成测试（issue #15）：
 * 公众出口（列表 / 搜索 / 归档 / 关注 Feed / 用户主页文章 / RSS / sitemap）一致性排除
 * PASSWORD 与 PRIVATE；详情口令门禁（id 与 slug 双路由）、口令校验端点签发令牌、
 * 哈希落库、回收站 / 评论 / 导出共存语义。
 * 业务错误统一 HTTP 200 + 业务码（GlobalExceptionHandler 既有行为），断言 $.code。
 *
 * <p>测试环境 StringRedisTemplate 是深桩 mock（读一律 null）：令牌签发后的放行链路
 * 用 Mockito 按令牌键定点打桩验证。
 *
 * @author Yoruhaki
 */
class ArticleVisibilityIntegrationTests extends IntegrationTestSupport {

    /**
     * 访问令牌请求头（与 ArticleController 契约一致）
     */
    private static final String ACCESS_TOKEN_HEADER = "X-Article-Access-Token";

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 预置 AUTHOR 并以其身份创建文章，返回文章 id；visibility / password 可空（空 = 不带该字段）
     */
    private long createWithVisibility(String bearer, String title, String status,
                                      String visibility, String password) throws Exception {
        StringBuilder body = new StringBuilder("""
                {"title":"%s","content":"# 正文","editorType":"MARKDOWN","status":"%s"
                """.formatted(title, status));
        if (visibility != null) {
            body.append(",\"visibility\":\"").append(visibility).append("\"");
        }
        if (password != null) {
            body.append(",\"accessPassword\":\"").append(password).append("\"");
        }
        body.append("}");
        MvcResult created = mockMvc.perform(post("/articles")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").asLong();
    }

    private String slugOf(long articleId) {
        return jdbcTemplate.queryForObject("SELECT slug FROM article WHERE id = ?",
                String.class, articleId);
    }

    @Test
    void passwordAndPrivateArticles_excludedFromAllPublicOutlets() throws Exception {
        String authorName = unique("it_vis_author_");
        long authorId = seedUser(authorName, "可见性作者", "AUTHOR");
        String bearer = bearerOf(authorName);
        String publicTitle = unique("公开文章Pub");
        String passwordTitle = unique("口令文章Pwd");
        String privateTitle = unique("私密文章Pvt");
        createWithVisibility(bearer, publicTitle, "PUBLISHED", null, null);
        long passwordId = createWithVisibility(bearer, passwordTitle, "PUBLISHED", "PASSWORD", "pwd-1234");
        long privateId = createWithVisibility(bearer, privateTitle, "PUBLISHED", "PRIVATE", null);

        // 公开列表：只见公开文章
        MvcResult list = mockMvc.perform(get("/articles").param("size", "100"))
                .andExpect(status().isOk()).andReturn();
        String listBody = list.getResponse().getContentAsString();
        assertTrue(listBody.contains(publicTitle), "公开列表应含公开文章");
        assertFalse(listBody.contains(passwordTitle), "公开列表不得含口令文章标题");
        assertFalse(listBody.contains(privateTitle), "公开列表不得含私密文章标题");

        // 全文搜索（H2 走 LIKE 兜底，同样经谓词收口）
        for (String kw : new String[]{passwordTitle, privateTitle}) {
            MvcResult searched = mockMvc.perform(get("/articles/search").param("keyword", kw))
                    .andExpect(status().isOk()).andReturn();
            assertFalse(searched.getResponse().getContentAsString().contains(kw),
                    "搜索结果不得含 " + kw);
        }
        MvcResult searchPublic = mockMvc.perform(get("/articles/search").param("keyword", publicTitle))
                .andExpect(status().isOk()).andReturn();
        assertTrue(searchPublic.getResponse().getContentAsString().contains(publicTitle),
                "搜索应能命中公开文章");

        // 归档
        MvcResult archives = mockMvc.perform(get("/articles/archives"))
                .andExpect(status().isOk()).andReturn();
        String archivesBody = archives.getResponse().getContentAsString();
        assertTrue(archivesBody.contains(publicTitle));
        assertFalse(archivesBody.contains(passwordTitle), "归档不得含口令文章标题");
        assertFalse(archivesBody.contains(privateTitle), "归档不得含私密文章标题");

        // 用户主页文章列表
        MvcResult authorArticles = mockMvc.perform(get("/users/{id}/articles", authorId))
                .andExpect(status().isOk()).andReturn();
        String authorBody = authorArticles.getResponse().getContentAsString();
        assertTrue(authorBody.contains(publicTitle));
        assertFalse(authorBody.contains(passwordTitle), "用户主页不得含口令文章标题");
        assertFalse(authorBody.contains(privateTitle), "用户主页不得含私密文章标题");

        // RSS / sitemap：标题与链接零泄露
        String passwordSlug = slugOf(passwordId);
        String privateSlug = slugOf(privateId);
        String publicSlug = slugOf(
                jdbcTemplate.queryForObject("SELECT id FROM article WHERE title = ?",
                        Long.class, publicTitle));
        MvcResult rss = mockMvc.perform(get("/rss.xml")).andExpect(status().isOk()).andReturn();
        String rssBody = rss.getResponse().getContentAsString();
        assertTrue(rssBody.contains(publicSlug), "RSS 应含公开文章");
        assertFalse(rssBody.contains(passwordTitle), "RSS 不得泄露口令文章标题");
        assertFalse(rssBody.contains(privateTitle), "RSS 不得泄露私密文章标题");
        assertFalse(rssBody.contains(passwordSlug), "RSS 不得含口令文章链接");
        assertFalse(rssBody.contains(privateSlug), "RSS 不得含私密文章链接");

        MvcResult sitemap = mockMvc.perform(get("/sitemap.xml")).andExpect(status().isOk()).andReturn();
        String sitemapBody = sitemap.getResponse().getContentAsString();
        assertTrue(sitemapBody.contains("/article/" + publicSlug), "sitemap 应含公开文章链接");
        assertFalse(sitemapBody.contains(passwordSlug), "sitemap 不得含口令文章链接");
        assertFalse(sitemapBody.contains(privateSlug), "sitemap 不得含私密文章链接");

        // 关注 Feed（登录用户关注作者后，口令 / 私密文章同样不进 Feed）
        String readerName = unique("it_vis_reader_");
        seedUser(readerName, "读者", "USER");
        String readerBearer = bearerOf(readerName);
        mockMvc.perform(post("/users/{id}/follow", authorId)
                        .header(HttpHeaders.AUTHORIZATION, readerBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
        MvcResult feed = mockMvc.perform(get("/feed").param("size", "100")
                        .header(HttpHeaders.AUTHORIZATION, readerBearer))
                .andExpect(status().isOk()).andReturn();
        String feedBody = feed.getResponse().getContentAsString();
        assertTrue(feedBody.contains(publicTitle), "Feed 应含公开文章");
        assertFalse(feedBody.contains(passwordTitle), "Feed 不得含口令文章标题");
        assertFalse(feedBody.contains(privateTitle), "Feed 不得含私密文章标题");
    }

    @Test
    void passwordArticle_detailGateOnBothIdAndSlugRoutes() throws Exception {
        String authorName = unique("it_pwd_gate_");
        seedUser(authorName, "口令作者", "AUTHOR");
        String bearer = bearerOf(authorName);
        String title = unique("门禁文章Gate");
        long articleId = createWithVisibility(bearer, title, "PUBLISHED", "PASSWORD", "pwd-1234");
        String slug = slugOf(articleId);

        // id 路由：口令门极简视图——证实存在与需口令，但不下发标题 / 正文 / 作者
        mockMvc.perform(get("/articles/{id}", articleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.passwordRequired").value(true))
                .andExpect(jsonPath("$.data.visibility").value("PASSWORD"))
                .andExpect(jsonPath("$.data.id").value(articleId))
                .andExpect(jsonPath("$.data.slug").value(slug))
                .andExpect(jsonPath("$.data.title").doesNotExist())
                .andExpect(jsonPath("$.data.content").doesNotExist());

        // slug 路由同一口径
        mockMvc.perform(get("/articles/slug/{slug}", slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passwordRequired").value(true))
                .andExpect(jsonPath("$.data.id").value(articleId))
                .andExpect(jsonPath("$.data.title").doesNotExist());
    }

    @Test
    void passwordArticle_verifyThenTokenUnlocksDetail() throws Exception {
        String authorName = unique("it_pwd_flow_");
        seedUser(authorName, "流程作者", "AUTHOR");
        String bearer = bearerOf(authorName);
        String title = unique("流程文章Flow");
        long articleId = createWithVisibility(bearer, title, "PUBLISHED", "PASSWORD", "pwd-1234");

        // 错误口令 → 40078
        mockMvc.perform(post("/articles/{id}/password", articleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrong\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40078"));

        // 正确口令 → 签发令牌
        MvcResult verified = mockMvc.perform(post("/articles/{id}/password", articleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"pwd-1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.expiresIn").value(7200))
                .andReturn();
        String token = objectMapper.readTree(verified.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
        assertFalse(token.isBlank(), "应签发访问令牌");

        // 令牌放行（Redis 深桩定点打桩：该令牌键存在）
        Mockito.when(stringRedisTemplate.opsForValue()
                        .get("blog:article-access:" + articleId + ":" + token))
                .thenReturn(jdbcTemplate.queryForObject(
                        "SELECT access_password_hash FROM article WHERE id = ?", String.class, articleId));
        mockMvc.perform(get("/articles/{id}", articleId).header(ACCESS_TOKEN_HEADER, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passwordRequired").value(false))
                .andExpect(jsonPath("$.data.title").value(title))
                .andExpect(jsonPath("$.data.content").exists());

        // 无效令牌仍回口令门
        mockMvc.perform(get("/articles/{id}", articleId).header(ACCESS_TOKEN_HEADER, "forged-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passwordRequired").value(true))
                .andExpect(jsonPath("$.data.title").doesNotExist());
    }

    @Test
    void passwordArticle_authorAndAdminBypassGate() throws Exception {
        String authorName = unique("it_pwd_self_");
        seedUser(authorName, "自看作者", "AUTHOR");
        String bearer = bearerOf(authorName);
        String title = unique("自看文章Self");
        long articleId = createWithVisibility(bearer, title, "PUBLISHED", "PASSWORD", "pwd-1234");

        // 作者本人：无需口令直接看全文
        mockMvc.perform(get("/articles/{id}", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passwordRequired").value(false))
                .andExpect(jsonPath("$.data.title").value(title));

        // ADMIN：同样直通
        String adminName = unique("it_pwd_admin_");
        seedUser(adminName, "管理员", "ADMIN");
        mockMvc.perform(get("/articles/{id}", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(adminName)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passwordRequired").value(false))
                .andExpect(jsonPath("$.data.title").value(title));
    }

    @Test
    void passwordEndpoint_requiresCaptchaAtArticleThreshold() throws Exception {
        String authorName = unique("it_pwd_limit_");
        seedUser(authorName, "限流作者", "AUTHOR");
        long id = createWithVisibility(bearerOf(authorName), unique("限流文章"), "PUBLISHED", "PASSWORD", "pwd-1234");
        String key = "captcha:count:article-password:a:" + id;
        Mockito.when(stringRedisTemplate.opsForValue().get(key)).thenReturn("5");
        mockMvc.perform(post("/articles/{id}/password", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"pwd-1234\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("40066"));
        Mockito.when(stringRedisTemplate.opsForValue().get("captcha:article-password:it-captcha")).thenReturn("abcd");
        mockMvc.perform(post("/articles/{id}/password", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"pwd-1234\",\"captchaId\":\"it-captcha\",\"captchaCode\":\"abcd\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("200"));
        Mockito.verify(stringRedisTemplate).delete(key);
    }

    @Test
    void passwordRotation_invalidatesExistingToken() throws Exception {
        String name = unique("it_pwd_rotate_");
        seedUser(name, "改口令作者", "AUTHOR");
        String bearer = bearerOf(name);
        long id = createWithVisibility(bearer, unique("改口令文章"), "PUBLISHED", "PASSWORD", "pwd-1234");
        String oldHash = jdbcTemplate.queryForObject("SELECT access_password_hash FROM article WHERE id = ?", String.class, id);
        Mockito.when(stringRedisTemplate.opsForValue().get("blog:article-access:" + id + ":old-token")).thenReturn(oldHash);
        mockMvc.perform(get("/articles/{id}", id).header(ACCESS_TOKEN_HEADER, "old-token"))
                .andExpect(jsonPath("$.data.passwordRequired").value(false));
        mockMvc.perform(put("/articles/{id}", id).header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"改口令文章\",\"content\":\"c\",\"editorType\":\"MARKDOWN\",\"accessPassword\":\"new-1234\"}"))
                .andExpect(jsonPath("$.code").value("200"));
        mockMvc.perform(get("/articles/{id}", id).header(ACCESS_TOKEN_HEADER, "old-token"))
                .andExpect(jsonPath("$.data.passwordRequired").value(true))
                .andExpect(jsonPath("$.data.content").doesNotExist());
    }

    @Test
    void privateArticle_anonymousSeesNotExistEverywhere() throws Exception {
        String authorName = unique("it_pvt_");
        seedUser(authorName, "私密作者", "AUTHOR");
        String bearer = bearerOf(authorName);
        String title = unique("私密文章Hide");
        long articleId = createWithVisibility(bearer, title, "PUBLISHED", "PRIVATE", null);
        String slug = slugOf(articleId);

        // 详情（id / slug）与口令校验端点均与「不存在」同语义，不暴露存在性
        mockMvc.perform(get("/articles/{id}", articleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40030"));
        mockMvc.perform(get("/articles/slug/{slug}", slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40030"));
        mockMvc.perform(post("/articles/{id}/password", articleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"any\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40030"));

        // 作者本人可见
        mockMvc.perform(get("/articles/{id}", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value(title))
                .andExpect(jsonPath("$.data.visibility").value("PRIVATE"));
    }

    @Test
    void passwordArticle_inTrashNotGatedNotVerifiable() throws Exception {
        // 与回收站共存：口令文章删除后对公众与「不存在」同语义（不出口令框、口令不可验）
        String authorName = unique("it_pwd_trash_");
        seedUser(authorName, "回收作者", "AUTHOR");
        String bearer = bearerOf(authorName);
        long articleId = createWithVisibility(bearer, unique("回收口令文章"), "PUBLISHED", "PASSWORD", "pwd-1234");

        mockMvc.perform(delete("/articles/{id}", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        mockMvc.perform(get("/articles/{id}", articleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40030"));
        mockMvc.perform(post("/articles/{id}/password", articleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"pwd-1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40030"));

        // 恢复回草稿后依旧对公众不可见（回草稿不直接上线）
        mockMvc.perform(post("/articles/{id}/restore", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
        mockMvc.perform(get("/articles/{id}", articleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40030"));
    }

    @Test
    void passwordArticle_commentAndExportFollowDetailVisibility() throws Exception {
        String authorName = unique("it_pwd_side_");
        long authorId = seedUser(authorName, "侧边作者", "AUTHOR");
        String bearer = bearerOf(authorName);
        long articleId = createWithVisibility(bearer, unique("侧边文章Side"), "PUBLISHED", "PASSWORD", "pwd-1234");

        // 评论准入：口令文章对公众等同「未发布」，评论落不进去
        String commenterName = unique("it_pwd_cmt_");
        seedUser(commenterName, "评论者", "USER");
        mockMvc.perform(post("/comments")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(commenterName))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"articleId\":" + articleId + ",\"content\":\"路过\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40042"));

        // 导出可见性与详情一致：匿名导口令文章与不存在同语义
        mockMvc.perform(get("/articles/{id}/export", articleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40030"));
        // 从公开改成受保护后，已有评论的正文也不能经匿名接口绕过门禁。
        mockMvc.perform(get("/comments").param("articleId", String.valueOf(articleId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40030"));
    }

    @Test
    void createVisibility_validationAndHashStorage() throws Exception {
        String authorName = unique("it_vis_valid_");
        seedUser(authorName, "校验作者", "AUTHOR");
        String bearer = bearerOf(authorName);

        // PASSWORD 缺口令 → 40077
        mockMvc.perform(post("/articles")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"content\":\"c\",\"editorType\":\"MARKDOWN\","
                                + "\"status\":\"DRAFT\",\"visibility\":\"PASSWORD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40077"));

        // 非法可见性 → 40076
        mockMvc.perform(post("/articles")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"content\":\"c\",\"editorType\":\"MARKDOWN\","
                                + "\"status\":\"DRAFT\",\"visibility\":\"HIDDEN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40076"));

        // 口令过短 → 40023
        mockMvc.perform(post("/articles")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"content\":\"c\",\"editorType\":\"MARKDOWN\","
                                + "\"status\":\"DRAFT\",\"visibility\":\"PASSWORD\",\"accessPassword\":\"abc\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40023"));

        // 正常创建：库内只落 BCrypt 哈希，明文口令不出现
        long articleId = createWithVisibility(bearer, unique("哈希文章"), "DRAFT", "PASSWORD", "plain-7788");
        String hash = jdbcTemplate.queryForObject(
                "SELECT access_password_hash FROM article WHERE id = ?", String.class, articleId);
        assertTrue(hash != null && hash.startsWith("$2"), "口令应以 BCrypt 哈希落库，实际 " + hash);
        assertNotEquals("plain-7788", hash, "明文口令不得落库");
    }

    @Test
    void updateVisibility_switchRoundClearsHash() throws Exception {
        String authorName = unique("it_vis_edit_");
        seedUser(authorName, "切换作者", "AUTHOR");
        String bearer = bearerOf(authorName);
        long articleId = createWithVisibility(bearer, unique("切换文章"), "DRAFT", "PUBLIC", null);

        // PUBLIC → PASSWORD（带口令）
        mockMvc.perform(put("/articles/{id}", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"切换文章\",\"content\":\"c\",\"editorType\":\"MARKDOWN\","
                                + "\"status\":\"DRAFT\",\"visibility\":\"PASSWORD\",\"accessPassword\":\"pw-9988\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
        String hash = jdbcTemplate.queryForObject(
                "SELECT access_password_hash FROM article WHERE id = ?", String.class, articleId);
        assertTrue(hash != null && hash.startsWith("$2"), "切入 PASSWORD 应落哈希");

        // PASSWORD → PUBLIC：哈希清空（口令不残留在库）
        mockMvc.perform(put("/articles/{id}", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"切换文章\",\"content\":\"c\",\"editorType\":\"MARKDOWN\","
                                + "\"status\":\"DRAFT\",\"visibility\":\"PUBLIC\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
        String cleared = jdbcTemplate.queryForObject(
                "SELECT access_password_hash FROM article WHERE id = ?", String.class, articleId);
        assertTrue(cleared == null || cleared.isBlank(), "切回 PUBLIC 应清空口令哈希");

        // 缺省 visibility = 保持现状：先切到 PRIVATE 再不传 visibility 保存，仍为 PRIVATE
        mockMvc.perform(put("/articles/{id}", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"切换文章\",\"content\":\"c\",\"editorType\":\"MARKDOWN\","
                                + "\"status\":\"DRAFT\",\"visibility\":\"PRIVATE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
        mockMvc.perform(put("/articles/{id}", articleId)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"切换文章\",\"content\":\"c2\",\"editorType\":\"MARKDOWN\","
                                + "\"status\":\"DRAFT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
        String visibility = jdbcTemplate.queryForObject(
                "SELECT visibility FROM article WHERE id = ?", String.class, articleId);
        assertTrue("PRIVATE".equals(visibility), "不传 visibility 应保持现状，实际 " + visibility);
    }
}
