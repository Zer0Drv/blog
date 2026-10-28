package com.zer0drv.blog.realit;

import com.zer0drv.blog.sysconfig.service.SysConfigService;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.RemoveBucketArgs;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Search is exercised over anonymous HTTP with real MySQL ngram indexes and committed fixtures. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.location=classpath:/application-real-it.yaml")
@ContextConfiguration(initializers = RealInfrastructureSafety.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchRealIT {
    private static final String RUN = UUID.randomUUID().toString().replace("-", "");
    private static final String BUCKET = "blog-it-" + RUN;
    private static final String FULLTEXT_KEY = "blog.search.fulltext-enabled";
    private static final LocalDateTime PUBLISHED = LocalDateTime.of(2020, 6, 10, 12, 0);
    private static final String INSERT_ARTICLE = """
            INSERT INTO article(title,summary,content,content_text,editor_type,category_id,author_id,
                                status,visibility,publish_time,deleted)
            VALUES(?,?,?,?,'MARKDOWN',?,?,'PUBLISHED','PUBLIC',?,0)
            """;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private ObjectMapper json;
    @Autowired private SysConfigService config;
    @Autowired private MinioClient minio;
    private long userId, categoryId, otherCategoryId, tagId, otherTagId;
    private String username, originalFulltextOverride;

    @DynamicPropertySource
    static void isolate(DynamicPropertyRegistry registry) {
        registry.add("blog.totp.encryption-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        registry.add("blog.minio.bucket", () -> BUCKET);
        registry.add("blog.upload-dir", () -> Path.of("target", "real-it-uploads", RUN).toAbsolutePath().toString());
    }

    @BeforeAll
    void requireRealMysql() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            assertTrue(connection.getMetaData().getDatabaseMajorVersion() >= 8);
        }
        assertEquals(2, jdbc.queryForObject("SELECT @@ngram_token_size", Integer.class));
        assertEquals("00:00:00", jdbc.queryForObject("SELECT TIMEDIFF(NOW(),UTC_TIMESTAMP())", String.class));
        assertTrue(minio.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build()));
    }

    @BeforeEach
    void seedOwnedNamespace() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        username = "search_" + suffix;
        jdbc.update("INSERT INTO `user`(username,email,nickname,role,status) VALUES(?,?,'Search IT','AUTHOR',0)",
                username, username + "@real-it.invalid");
        userId = jdbc.queryForObject("SELECT id FROM `user` WHERE username=?", Long.class, username);
        jdbc.update("INSERT INTO category(name) VALUES(?),(?)", "search-" + suffix, "other-" + suffix);
        categoryId = jdbc.queryForObject("SELECT id FROM category WHERE name=?", Long.class, "search-" + suffix);
        otherCategoryId = jdbc.queryForObject("SELECT id FROM category WHERE name=?", Long.class, "other-" + suffix);
        jdbc.update("INSERT INTO tag(name) VALUES(?),(?)", "search-" + suffix, "other-" + suffix);
        tagId = jdbc.queryForObject("SELECT id FROM tag WHERE name=?", Long.class, "search-" + suffix);
        otherTagId = jdbc.queryForObject("SELECT id FROM tag WHERE name=?", Long.class, "other-" + suffix);
        originalFulltextOverride = jdbc.queryForObject("SELECT config_value FROM sys_config WHERE config_key=?", String.class, FULLTEXT_KEY);
        config.update(Map.of(FULLTEXT_KEY, true), userId);
    }

    @AfterEach
    void removeOnlyOwnedRowsAndRestoreConfig() {
        jdbc.update("UPDATE sys_config SET config_value=? WHERE config_key=?", originalFulltextOverride, FULLTEXT_KEY);
        config.refresh();
        jdbc.update("DELETE FROM sys_config_audit WHERE operator_id=?", userId);
        jdbc.update("DELETE FROM article_tag WHERE article_id IN (SELECT id FROM article WHERE author_id=?)", userId);
        jdbc.update("DELETE FROM article WHERE author_id=?", userId);
        jdbc.update("DELETE FROM tag WHERE id IN (?,?)", tagId, otherTagId);
        jdbc.update("DELETE FROM category WHERE id IN (?,?)", categoryId, otherCategoryId);
        jdbc.update("DELETE FROM `user` WHERE id=? AND username=?", userId, username);
    }

    @AfterAll
    void removeEmptyRunBucket() throws Exception {
        try {
            // These tests never upload objects; deleting a nonempty bucket must fail, not erase foreign data.
            minio.removeBucket(RemoveBucketArgs.builder().bucket(BUCKET).build());
        } finally {
            http.close();
        }
    }

    @Test
    void combinedFiltersUseInclusiveCalendarDatesAndStablePagesInBothModes() throws Exception {
        long first = article("蓝鲸首篇", "蓝鲸正文", PUBLISHED.withHour(0));
        long second = article("蓝鲸次篇", "蓝鲸正文", PUBLISHED.withHour(23).withMinute(59).withSecond(59));
        long third = article("蓝鲸末篇", "蓝鲸正文", PUBLISHED.withHour(23).withMinute(59).withSecond(59));
        long outside = article("蓝鲸次日", "蓝鲸正文", PUBLISHED.plusDays(1).withHour(0));
        long before = article("蓝鲸前日", "蓝鲸正文", PUBLISHED.minusDays(1).withHour(23));
        long untagged = article("蓝鲸无标签", "蓝鲸正文", PUBLISHED);
        long otherCategory = article("蓝鲸其他分类", "蓝鲸正文", PUBLISHED);
        jdbc.update("UPDATE article SET category_id=? WHERE id=?", otherCategoryId, otherCategory);
        for (long id : List.of(first, second, third, outside, before, otherCategory)) tag(id, tagId);
        tag(first, otherTagId); // Multiple tags must not duplicate records or inflate COUNT.
        tag(untagged, otherTagId);
        Map<String, Object> params = params("蓝鲸");
        params.putAll(Map.of("tagId", tagId, "publishedFrom", "2020-06-10", "publishedTo", "2020-06-10", "sort", "newest", "size", 2));
        for (boolean enabled : List.of(true, false)) {
            config.update(Map.of(FULLTEXT_KEY, enabled), userId);
            JsonNode page1 = search(params);
            assertEquals(enabled ? "FULLTEXT" : "LIKE", page1.path("searchMode").asString());
            assertEquals(3, page1.path("total").asLong());
            assertEquals(List.of(third, second), ids(page1));
            params.put("page", 2);
            JsonNode page2 = search(params);
            assertEquals(3, page2.path("total").asLong());
            assertEquals(List.of(first), ids(page2));
            params.put("page", 1);
            params.put("sort", "oldest");
            assertEquals(List.of(first, second), ids(search(params)));
            params.put("sort", "newest");
        }
        // Clearing all keyword/tag/date constraints returns every public article in this category.
        JsonNode cleared = search(params(""));
        assertEquals(6, cleared.path("total").asLong());
        assertEquals("LIKE", cleared.path("searchMode").asString());
        assertEquals("newest", cleared.path("effectiveSort").asString());
    }

    @Test
    void fulltextRelevanceMatchesMysqlScoreOrderWithDeterministicTieBreaks() throws Exception {
        article("蓝鲸蓝鲸蓝鲸", "蓝鲸在海里", PUBLISHED.minusDays(3));
        article("观测记录", "蓝鲸" + "普通正文".repeat(20), PUBLISHED);
        article("相同记录", "蓝鲸相同正文", PUBLISHED);
        article("相同记录", "蓝鲸相同正文", PUBLISHED);
        article("完全无关", "森林与山川", PUBLISHED);
        List<Long> expected = jdbc.queryForList("""
                SELECT id FROM article WHERE category_id=? AND MATCH(title,content_text) AGAINST(? IN NATURAL LANGUAGE MODE)
                ORDER BY MATCH(title,content_text) AGAINST(? IN NATURAL LANGUAGE MODE) DESC,publish_time DESC,id DESC
                """, Long.class, categoryId, "蓝鲸", "蓝鲸");
        assertEquals(4, expected.size());
        JsonNode found = search(params("蓝鲸"));
        assertEquals("FULLTEXT", found.path("searchMode").asString());
        assertEquals("relevance", found.path("effectiveSort").asString());
        assertEquals(expected, ids(found));
        config.update(Map.of(FULLTEXT_KEY, false), userId);
        JsonNode fallback = search(params("蓝鲸"));
        assertEquals("LIKE", fallback.path("searchMode").asString());
        assertEquals("newest", fallback.path("effectiveSort").asString());
        var newest = new ArrayList<>(expected);
        newest.sort(Comparator.reverseOrder());
        assertEquals(newest, ids(fallback));
    }

    @Test
    void everyRestrictedStateIsExcludedFromRecordsSnippetsAndCounts() throws Exception {
        long visible = article("蓝鲸公开", "公开正文片段", PUBLISHED);
        Map<String, Object[]> hidden = Map.of(
                "draft", new Object[]{"DRAFT", "PUBLIC", PUBLISHED, 0},
                "offline", new Object[]{"OFFLINE", "PUBLIC", PUBLISHED, 0},
                "private", new Object[]{"PUBLISHED", "PRIVATE", PUBLISHED, 0},
                "password", new Object[]{"PUBLISHED", "PASSWORD", PUBLISHED, 0},
                "future", new Object[]{"PUBLISHED", "PUBLIC", LocalDateTime.now().plusDays(1), 0},
                "no-date", new Object[]{"PUBLISHED", "PUBLIC", null, 0},
                "deleted", new Object[]{"PUBLISHED", "PUBLIC", PUBLISHED, 1});
        for (var entry : hidden.entrySet()) {
            long id = article("蓝鲸秘密-" + entry.getKey(), "不可泄露片段-" + entry.getKey(), PUBLISHED);
            Object[] state = entry.getValue();
            jdbc.update("UPDATE article SET status=?,visibility=?,publish_time=?,deleted=? WHERE id=?",
                    state[0], state[1], state[2], state[3], id);
        }
        for (boolean enabled : List.of(true, false)) {
            config.update(Map.of(FULLTEXT_KEY, enabled), userId);
            for (String keyword : List.of("蓝鲸", "")) {
                JsonNode found = search(params(keyword));
                assertEquals(1, found.path("total").asLong());
                assertEquals(List.of(visible), ids(found));
                assertFalse(found.toString().contains("秘密"));
                assertFalse(found.toString().contains("不可泄露"));
            }
        }
    }

    @Test
    void shortAndSpecialKeywordsAreLiteralAndSnippetsContainSafeBoundedContext() throws Exception {
        Map<String, Long> expected = new LinkedHashMap<>();
        expected.put("鲸", article("海洋观测", "前文".repeat(180) + "鲸在这里" + "后文".repeat(180), PUBLISHED));
        long summaryOnly = article("折扣记录", "正文没有折扣数值", PUBLISHED);
        jdbc.update("UPDATE article SET summary='恰好有 50% 优惠' WHERE id=?", summaryOnly);
        expected.put("%", summaryOnly);
        expected.put("_", article("变量记录", "变量 snake_case", PUBLISHED));
        expected.put("\\", article("路径记录", "路径 C:\\docs", PUBLISHED));
        expected.put("!", article("感叹记录", "请注意!", PUBLISHED));
        expected.put("<img", article("恶意标记样本", "前文 <img src=x onerror=alert(1)> 后文", PUBLISHED));
        String longKeyword = "「" + "完整命中".repeat(44) + "」";
        expected.put(longKeyword, article("长关键词", "前文".repeat(100) + longKeyword + "后文".repeat(100), PUBLISHED));
        article("无关文字", "完全没有这些符号", PUBLISHED);
        for (var entry : expected.entrySet()) {
            JsonNode found = search(params(entry.getKey()));
            assertEquals("LIKE", found.path("searchMode").asString());
            assertEquals(1, found.path("total").asLong(), entry.getKey());
            assertEquals(List.of(entry.getValue()), ids(found));
            JsonNode record = found.path("records").get(0);
            String snippet = record.path("snippet").asString();
            assertTrue(snippet.codePointCount(0, snippet.length()) <= 202);
            // Code samples remain literal text; the frontend must interpolate this string rather than use v-html.
            if (entry.getKey().equals("<img")) assertEquals("前文 <img src=x onerror=alert(1)> 后文", snippet);
            assertFalse(record.has("content"));
            assertFalse(record.has("contentText"));
            if (!entry.getKey().equals("<img")) assertTrue(snippet.contains(entry.getKey()), entry.getKey());
        }
        JsonNode empty = search(params("宇宙飞船"));
        assertEquals(0, empty.path("total").asLong());
        assertTrue(empty.path("records").isEmpty());
    }

    @Test
    void invalidRangesAndOversizedQueriesFailBeforeReturningData() throws Exception {
        List<Map<String, Object>> invalid = List.of(
                Map.of("publishedFrom", "2020-06-11", "publishedTo", "2020-06-10"),
                Map.of("publishedFrom", "2020-02-30"), Map.of("sort", "id desc"),
                Map.of("size", 101), Map.of("page", 0), Map.of("categoryId", -1),
                Map.of("keyword", "鲸".repeat(201)));
        for (Map<String, Object> values : invalid) {
            JsonNode response = request(values);
            assertNotEquals("200", response.path("code").asString(), values::toString);
            assertTrue(response.path("data").isMissingNode() || response.path("data").isNull());
        }
    }

    @Test
    void representativeThousandArticleDatasetProducesCorrectResultsAndTimingEvidence() throws Exception {
        int count = 1000, expectedMatches = 200;
        List<Object[]> rows = new ArrayList<>();
        String padding = "常规正文段落用于检验查询耗时与返回片段边界。".repeat(30);
        for (int i = 0; i < count; i++) {
            String body = padding + (i % 5 == 0 ? "蓝鲸观测" : "普通随笔") + padding;
            rows.add(new Object[]{"统计样本" + i, "摘要", body, body, categoryId, userId, PUBLISHED.plusSeconds(i)});
        }
        // No @Transactional: ngram indexes only expose committed rows to independent HTTP requests.
        jdbc.batchUpdate(INSERT_ARTICLE, rows);
        List<Map<String, Object>> samples = new ArrayList<>();
        Map<String, Object> params = params("蓝鲸");
        params.putAll(Map.of("sort", "newest", "size", 100));
        List<Long> expected = jdbc.queryForList("""
                SELECT id FROM article WHERE category_id=? AND content_text LIKE '%蓝鲸%'
                ORDER BY publish_time DESC,id DESC
                """, Long.class, categoryId);
        assertEquals(expectedMatches, expected.size());
        for (boolean enabled : List.of(true, false)) {
            config.update(Map.of(FULLTEXT_KEY, enabled), userId);
            search(params); // Warm connection/query paths; timings below are end-to-end HTTP, not database-only.
            List<Double> milliseconds = new ArrayList<>();
            for (int repeat = 0; repeat < 7; repeat++) {
                long start = System.nanoTime();
                JsonNode result = search(params);
                milliseconds.add((System.nanoTime() - start) / 1_000_000.0);
                assertEquals(expectedMatches, result.path("total").asLong());
                assertEquals(expected.subList(0, 100), ids(result));
                assertEquals(enabled ? "FULLTEXT" : "LIKE", result.path("searchMode").asString());
            }
            params.put("page", 2);
            assertEquals(expected.subList(100, 200), ids(search(params)));
            params.remove("page");
            List<Double> sorted = milliseconds.stream().sorted().toList();
            samples.add(Map.of("mode", enabled ? "FULLTEXT" : "LIKE", "httpMs", milliseconds,
                    "medianMs", sorted.get(3), "maxMs", sorted.getLast(), "matchedRows", expectedMatches,
                    "verifiedPages", 2, "pageSize", 100));
        }
        Path evidence = Path.of("target", "real-it-reports", "search-benchmark.json");
        Files.createDirectories(evidence.getParent());
        Files.writeString(evidence, json.writeValueAsString(Map.of("runId", RUN, "datasetRows", count,
                "bodyCharacters", rows.getFirst()[2].toString().length(), "keyword", "蓝鲸", "sort", "newest",
                "scope", "isolated local MySQL; warm end-to-end HTTP; includes filters, count and result assembly; not production SLA",
                "measuredAt", java.time.Instant.now().toString(), "samples", samples)));
    }

    private long article(String title, String content, LocalDateTime published) {
        jdbc.update(INSERT_ARTICLE, title, "摘要", content, content, categoryId, userId, published);
        return jdbc.queryForObject("SELECT MAX(id) FROM article WHERE author_id=?", Long.class, userId);
    }

    private void tag(long article, long tag) {
        jdbc.update("INSERT INTO article_tag(article_id,tag_id) VALUES(?,?)", article, tag);
    }

    private Map<String, Object> params(String keyword) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keyword", keyword);
        result.put("categoryId", categoryId);
        return result;
    }

    private JsonNode search(Map<String, Object> params) throws Exception {
        JsonNode response = request(params);
        assertEquals("200", response.path("code").asString(), response::toString);
        return response.path("data");
    }

    private JsonNode request(Map<String, Object> params) throws Exception {
        StringJoiner query = new StringJoiner("&");
        params.forEach((key, value) -> query.add(key + "=" + URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8)));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/articles/search?" + query))
                .timeout(Duration.ofSeconds(15)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() < 500, response::body);
        return json.readTree(response.body());
    }

    private List<Long> ids(JsonNode data) {
        List<Long> result = new ArrayList<>();
        data.path("records").forEach(record -> result.add(record.path("id").asLong()));
        return result;
    }
}
