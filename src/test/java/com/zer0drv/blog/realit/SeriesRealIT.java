package com.zer0drv.blog.realit;

import com.zer0drv.blog.attachment.service.AttachmentReferenceSnapshot;
import com.zer0drv.blog.auth.service.TokenService;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.RemoveBucketArgs;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Anonymous and authenticated HTTP against real MySQL/Flyway/Redis, including competing transactions. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.location=classpath:/application-real-it.yaml")
@ContextConfiguration(initializers = RealInfrastructureSafety.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeriesRealIT {
    private static final String RUN = UUID.randomUUID().toString().replace("-", "");
    private static final String BUCKET = "blog-it-" + RUN;
    private static final LocalDateTime PUBLISHED = LocalDateTime.of(2020, 6, 10, 12, 0);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final List<Account> accounts = new ArrayList<>();
    private final List<String> cleanupObjectKeys = new ArrayList<>();
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private ObjectMapper json;
    @Autowired private TokenService tokens;
    @Autowired private StringRedisTemplate redis;
    @Autowired private MinioClient minio;
    @Autowired private AttachmentReferenceSnapshot references;
    private Account author, other, admin, user;
    private String prefix;

    @DynamicPropertySource
    static void isolate(DynamicPropertyRegistry registry) {
        registry.add("blog.totp.encryption-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        registry.add("blog.minio.bucket", () -> BUCKET);
        registry.add("blog.upload-dir", () -> Path.of("target", "real-it-uploads", RUN).toAbsolutePath().toString());
    }

    @BeforeAll
    void requireRealInfrastructureAndMigration() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            assertTrue(connection.getMetaData().getDatabaseMajorVersion() >= 8);
        }
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='21' AND success=1", Long.class) > 0);
        assertTrue(minio.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build()));
    }

    @BeforeEach
    void seedOwnedAccounts() {
        prefix = "series-" + UUID.randomUUID().toString().replace("-", "");
        author = account("AUTHOR");
        other = account("AUTHOR");
        admin = account("ADMIN");
        user = account("USER");
    }

    @AfterEach
    void cleanupOwnedFixtures() {
        for (String key : cleanupObjectKeys) jdbc.update("DELETE FROM attachment_cleanup WHERE object_key=?", key);
        cleanupObjectKeys.clear();
        for (Account account : accounts) {
            jdbc.update("DELETE FROM article_series_item WHERE series_id IN (SELECT id FROM article_series WHERE owner_id=?)", account.id());
            jdbc.update("DELETE FROM article_series WHERE owner_id=?", account.id());
            jdbc.update("DELETE FROM article WHERE author_id=?", account.id());
            jdbc.update("DELETE FROM `user` WHERE id=? AND username=?", account.id(), account.username());
            redis.delete("blog:user-tokens:" + account.id());
        }
        accounts.clear();
    }

    @AfterAll
    void cleanupEmptyBucketAndClient() throws Exception {
        try {
            minio.removeBucket(RemoveBucketArgs.builder().bucket(BUCKET).build());
        } finally {
            http.close();
        }
    }

    @Test
    void createPublishReorderRemoveHideAndDeleteFollowTheSavedRevision() throws Exception {
        long first = article(author, "第一章"), second = article(author, "第二章"), third = article(author, "第三章");
        JsonNode created = create(author, "DRAFT", List.of(second, first));
        long id = created.path("id").asLong();
        String slug = created.path("slug").asString();
        assertEquals(0, created.path("revision").asLong());
        assertEquals(List.of(second, first), articleIds(created));
        assertCode(40085, send("GET", "/series/" + slug, null, null));
        assertTrue(nav(first).isEmpty());
        JsonNode published = update(author, created, "PUBLISHED", List.of(second, first));
        assertEquals(1, published.path("revision").asLong());
        JsonNode visible = detail(slug);
        assertEquals(2, visible.path("articleCount").asLong());
        assertEquals(List.of(second, first), articleIds(visible));
        assertFalse(visible.has("revision"));
        JsonNode firstNavigation = nav(second).get(0);
        assertEquals(id, firstNavigation.path("id").asLong());
        assertTrue(firstNavigation.path("previous").isNull());
        assertEquals(first, firstNavigation.path("next").path("id").asLong());
        assertTrue(sitemap().contains("/series/" + slug));
        assertTrue(containsId(ok(send("GET", "/series?size=100", null, null)).path("records"), id));

        JsonNode reordered = update(author, published, "PUBLISHED", List.of(first, third, second));
        JsonNode middleNavigation = nav(third).get(0);
        assertEquals(first, middleNavigation.path("previous").path("id").asLong());
        assertEquals(second, middleNavigation.path("next").path("id").asLong());
        assertEquals(List.of(first, third, second), articleIds(detail(slug)));
        JsonNode removed = update(author, reordered, "PUBLISHED", List.of(second, first));
        assertTrue(nav(third).isEmpty());
        JsonNode hidden = update(author, removed, "DRAFT", List.of(second, first));
        assertCode(40085, send("GET", "/series/" + slug, null, null));
        assertTrue(nav(first).isEmpty());
        assertFalse(sitemap().contains("/series/" + slug));
        assertFalse(containsId(ok(send("GET", "/series?size=100", null, null)).path("records"), id));
        ok(send("DELETE", "/series/manage/" + id + "?revision=" + hidden.path("revision").asLong(), null, author));
        assertCode(40085, send("GET", "/series/manage/" + id, null, author));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM article_series_item WHERE series_id=?", Integer.class, id));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM article WHERE author_id=?", Integer.class, author.id()));
    }

    @Test
    void authorsCannotCrossOwnershipAndAdminsKeepTheSeriesOwnerArticleBoundary() throws Exception {
        long own = article(author, "自己文章"), foreign = article(other, "他人文章"), adminArticle = article(admin, "管理员文章");
        assertCode(40023, send("POST", "/series/manage", payload("PUBLISHED", List.of(foreign)), author));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM article_series WHERE owner_id=?", Integer.class, author.id()));
        JsonNode created = create(author, "DRAFT", List.of(own));
        long id = created.path("id").asLong();
        Map<String, Object> body = savedPayload(created, "PUBLISHED", List.of(own));
        assertCode(40301, send("GET", "/series/manage/" + id, null, other));
        assertCode(40301, send("GET", "/series/manage/candidates?seriesId=" + id, null, other));
        assertCode(40301, send("PUT", "/series/manage/" + id, body, other));
        assertCode(40301, send("DELETE", "/series/manage/" + id + "?revision=0", null, other));
        for (String path : List.of("/series/manage", "/series/manage/" + id, "/series/manage/candidates")) {
            assertNotEquals(200, code(send("GET", path, null, user)));
            assertNotEquals(200, code(send("GET", path, null, null)));
        }
        assertNotEquals(200, code(send("POST", "/series/manage", body, user)));
        assertFalse(containsId(ok(send("GET", "/series/manage?size=100", null, other)).path("records"), id));
        assertCode(40301, send("GET", "/series/manage?all=true", null, author));
        assertTrue(containsId(ok(send("GET", "/series/manage?all=true&size=100", null, admin)).path("records"), id));
        JsonNode candidates = ok(send("GET", "/series/manage/candidates?seriesId=" + id + "&size=100", null, admin));
        assertTrue(containsId(candidates.path("records"), own));
        assertFalse(containsId(candidates.path("records"), foreign));
        assertFalse(containsId(candidates.path("records"), adminArticle));
        body.put("articleIds", List.of(adminArticle));
        assertCode(40023, send("PUT", "/series/manage/" + id, body, admin));
        assertEquals(List.of(own), articleIds(ok(send("GET", "/series/manage/" + id, null, author))));
        JsonNode edited = update(admin, created, "PUBLISHED", List.of(own));
        assertEquals(author.id(), jdbc.queryForObject("SELECT owner_id FROM article_series WHERE id=?", Long.class, id));
        assertEquals(1, edited.path("revision").asLong());
    }

    @Test
    void everyHiddenArticleStateAndSubsequentChangesImmediatelyFilterCountsAndNavigation() throws Exception {
        long first = article(author, "可见第一章"), last = article(author, "可见最后章");
        List<Long> hidden = new ArrayList<>();
        for (int i = 0; i < 7; i++) hidden.add(article(author, "不可泄露章节" + i));
        List<Long> ordered = new ArrayList<>();
        ordered.add(first); ordered.addAll(hidden); ordered.add(last);
        JsonNode created = create(author, "PUBLISHED", ordered);
        String slug = created.path("slug").asString();
        jdbc.update("UPDATE article SET status='DRAFT' WHERE id=?", hidden.get(0));
        jdbc.update("UPDATE article SET status='OFFLINE' WHERE id=?", hidden.get(1));
        jdbc.update("UPDATE article SET visibility='PRIVATE' WHERE id=?", hidden.get(2));
        jdbc.update("UPDATE article SET visibility='PASSWORD' WHERE id=?", hidden.get(3));
        jdbc.update("UPDATE article SET publish_time=? WHERE id=?", LocalDateTime.now().plusDays(1), hidden.get(4));
        jdbc.update("UPDATE article SET publish_time=NULL WHERE id=?", hidden.get(5));
        jdbc.update("UPDATE article SET deleted=1 WHERE id=?", hidden.get(6));
        JsonNode visible = detail(slug);
        assertEquals(2, visible.path("articleCount").asLong());
        assertEquals(List.of(first, last), articleIds(visible));
        assertFalse(visible.toString().contains("不可泄露"));
        assertFalse(visible.toString().contains("secret-body"));
        for (long hiddenId : hidden) assertTrue(nav(hiddenId).isEmpty());
        assertTrue(nav(Long.MAX_VALUE).isEmpty());
        assertEquals(last, nav(first).get(0).path("next").path("id").asLong());
        assertEquals(first, nav(last).get(0).path("previous").path("id").asLong());
        JsonNode listed = findById(ok(send("GET", "/series?size=100", null, null)).path("records"), created.path("id").asLong());
        assertEquals(2, listed.path("articleCount").asLong());
        String sitemap = sitemap();
        assertTrue(sitemap.contains("/series/" + slug));
        for (long hiddenId : hidden) assertFalse(sitemap.contains("/article/" + articleSlug(hiddenId) + "<"));

        jdbc.update("UPDATE article SET visibility='PRIVATE' WHERE id=?", last);
        assertEquals(List.of(first), articleIds(detail(slug)));
        assertEquals(1, nav(first).get(0).path("articleCount").asLong());
        assertTrue(nav(first).get(0).path("next").isNull());
        jdbc.update("UPDATE article SET status='OFFLINE' WHERE id=?", first);
        assertEquals(0, detail(slug).path("articleCount").asLong());
        assertTrue(nav(first).isEmpty());
        assertTrue(sitemap().contains("/series/" + slug)); // Public empty series remain valid.
        jdbc.update("UPDATE article SET publish_time=? WHERE id=?", PUBLISHED, hidden.get(4));
        assertEquals(List.of(hidden.get(4)), articleIds(detail(slug)));
        jdbc.update("DELETE FROM article WHERE id=?", hidden.get(4));
        assertEquals(0, detail(slug).path("articleCount").asLong());
        assertTrue(nav(hidden.get(4)).isEmpty());
    }

    @Test
    void membershipInSeveralSeriesKeepsEachIndependentChapterOrder() throws Exception {
        long first = article(author, "共同章节"), second = article(author, "甲系列后续"), third = article(author, "乙系列前置");
        JsonNode a = create(author, "PUBLISHED", List.of(first, second));
        JsonNode b = create(author, "PUBLISHED", List.of(third, first));
        JsonNode memberships = nav(first);
        assertEquals(2, memberships.size());
        JsonNode inA = findById(memberships, a.path("id").asLong());
        JsonNode inB = findById(memberships, b.path("id").asLong());
        assertNotNull(inA); assertNotNull(inB);
        assertTrue(inA.path("previous").isNull());
        assertEquals(second, inA.path("next").path("id").asLong());
        assertEquals(third, inB.path("previous").path("id").asLong());
        assertTrue(inB.path("next").isNull());
        update(author, a, "PUBLISHED", List.of(second));
        assertEquals(1, nav(first).size());
        assertEquals(b.path("id").asLong(), nav(first).get(0).path("id").asLong());
        assertEquals(List.of(third, first), articleIds(detail(b.path("slug").asString())));
    }

    @Test
    void concurrentUpdatesWithTheSameRevisionCommitOneWholeVersion() throws Exception {
        long first = article(author, "章节一"), second = article(author, "章节二"), third = article(author, "章节三");
        JsonNode created = create(author, "PUBLISHED", List.of(first, second));
        long id = created.path("id").asLong();
        Map<String, Object> a = savedPayload(created, "PUBLISHED", List.of(second, first));
        Map<String, Object> b = savedPayload(created, "DRAFT", List.of(third, second));
        a.put("title", "并发版本甲"); a.put("description", "甲的介绍");
        b.put("title", "并发版本乙"); b.put("description", "乙的介绍");
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        List<HttpResponse<String>> replies;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> { ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS)); return send("PUT", "/series/manage/" + id, a, author); });
            var two = pool.submit(() -> { ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS)); return send("PUT", "/series/manage/" + id, b, author); });
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            replies = List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
        }
        assertEquals(List.of(200, 40900), replies.stream().map(this::code).sorted().toList());
        int winner = code(replies.get(0)) == 200 ? 0 : 1;
        Map<String, Object> winnerBody = winner == 0 ? a : b;
        JsonNode saved = ok(send("GET", "/series/manage/" + id, null, author));
        assertEquals(1, saved.path("revision").asLong());
        assertEquals(winnerBody.get("title"), saved.path("title").asString());
        assertEquals(winnerBody.get("description"), saved.path("description").asString());
        assertEquals(winnerBody.get("status"), saved.path("status").asString());
        assertEquals(winnerBody.get("articleIds"), articleIds(saved));
        assertEquals(articleIds(saved), jdbc.queryForList("SELECT article_id FROM article_series_item WHERE series_id=? ORDER BY position", Long.class, id));
        assertCode(40900, send("DELETE", "/series/manage/" + id + "?revision=0", null, author));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM article_series WHERE id=?", Integer.class, id));
    }

    @Test
    void invalidChaptersOrSlugRollBackMetadataAndOrderingAndEmptySeriesRemainValid() throws Exception {
        long first = article(author, "初始章节"), foreign = article(other, "其他作者"), deleted = article(author, "已删除章节");
        jdbc.update("UPDATE article SET deleted=1 WHERE id=?", deleted);
        JsonNode created = create(author, "PUBLISHED", List.of(first));
        long id = created.path("id").asLong();
        for (List<Long> invalidIds : List.of(List.of(first, first), List.of(first, Long.MAX_VALUE), List.of(first, foreign), List.of(first, deleted))) {
            Map<String, Object> invalid = savedPayload(created, "DRAFT", invalidIds);
            invalid.put("title", "不应写入的标题"); invalid.put("description", "不应写入的描述");
            assertCode(40023, send("PUT", "/series/manage/" + id, invalid, author));
            JsonNode unchanged = ok(send("GET", "/series/manage/" + id, null, author));
            assertEquals(created.path("title"), unchanged.path("title"));
            assertEquals(0, unchanged.path("revision").asLong());
            assertEquals("PUBLISHED", unchanged.path("status").asString());
            assertEquals(List.of(first), articleIds(unchanged));
        }
        Map<String, Object> duplicateSlug = payload("DRAFT", List.of());
        duplicateSlug.put("slug", created.path("slug").asString());
        assertCode(40086, send("POST", "/series/manage", duplicateSlug, author));
        Map<String, Object> noRevision = savedPayload(created, "PUBLISHED", List.of(first));
        noRevision.remove("revision");
        assertNotEquals(200, code(send("PUT", "/series/manage/" + id, noRevision, author)));
        JsonNode empty = update(author, created, "PUBLISHED", List.of());
        assertEquals(0, detail(empty.path("slug").asString()).path("articleCount").asLong());
        assertTrue(articleIds(detail(empty.path("slug").asString())).isEmpty());
        assertTrue(nav(first).isEmpty());
    }

    @Test
    void seriesCoversAreRetainedAndPurgingObjectsCannotBeNewReferences() throws Exception {
        String retainedName = UUID.randomUUID().toString().replace("-", "") + ".png";
        Map<String, Object> body = payload("DRAFT", List.of());
        body.put("cover", "http://127.0.0.1:29000/" + BUCKET + "/" + retainedName);
        JsonNode created = ok(send("POST", "/series/manage", body, author));
        assertTrue(references.load().contains(retainedName));
        String blockedKey = "202610/" + UUID.randomUUID().toString().replace("-", "") + ".png";
        cleanupObjectKeys.add(blockedKey);
        jdbc.update("""
                INSERT INTO attachment_cleanup(storage,object_key,size_bytes,fingerprint,state,marked_at,eligible_at,operator_id)
                VALUES('MINIO',?,1,'series-fixture','PURGING',NOW(),NOW(),?)
                """, blockedKey, author.id());
        Map<String, Object> invalid = savedPayload(created, "PUBLISHED", List.of());
        invalid.put("cover", "http://127.0.0.1:29000/" + BUCKET + "/" + blockedKey);
        assertCode(40023, send("PUT", "/series/manage/" + created.path("id").asLong(), invalid, author));
        JsonNode saved = ok(send("GET", "/series/manage/" + created.path("id").asLong(), null, author));
        assertEquals(created.path("cover"), saved.path("cover"));
        assertEquals(0, saved.path("revision").asLong());
        assertTrue(references.load().contains(retainedName));
    }

    private Account account(String role) {
        String username = "ser_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO `user`(username,email,nickname,role,status) VALUES(?,?,'Series IT',?,0)", username, username + "@real-it.invalid", role);
        long id = jdbc.queryForObject("SELECT id FROM `user` WHERE username=?", Long.class, username);
        Account account = new Account(id, username, tokens.generateToken(id, List.of("ROLE_" + role)));
        accounts.add(account);
        return account;
    }

    private long article(Account owner, String title) {
        String slug = prefix + "-article-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO article(title,slug,summary,content,content_text,editor_type,author_id,status,visibility,publish_time,deleted)
                VALUES(?,?,'secret-summary','secret-body','secret-body','MARKDOWN',?,'PUBLISHED','PUBLIC',?,0)
                """, title, slug, owner.id(), PUBLISHED);
        return jdbc.queryForObject("SELECT id FROM article WHERE slug=?", Long.class, slug);
    }

    private String articleSlug(long id) { return jdbc.queryForObject("SELECT slug FROM article WHERE id=?", String.class, id); }

    private Map<String, Object> payload(String status, List<Long> articleIds) {
        return new LinkedHashMap<>(Map.of("title", "系列 " + prefix, "description", "系列介绍", "cover", "",
                "slug", prefix + "-" + UUID.randomUUID().toString().substring(0, 8), "status", status, "articleIds", articleIds));
    }

    private Map<String, Object> savedPayload(JsonNode saved, String status, List<Long> articleIds) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (String field : List.of("title", "description", "cover", "slug")) body.put(field, saved.path(field).asString());
        body.put("status", status); body.put("articleIds", articleIds); body.put("revision", saved.path("revision").asLong());
        return body;
    }

    private JsonNode create(Account owner, String status, List<Long> ids) throws Exception {
        return ok(send("POST", "/series/manage", payload(status, ids), owner));
    }
    private JsonNode update(Account operator, JsonNode saved, String status, List<Long> ids) throws Exception {
        return ok(send("PUT", "/series/manage/" + saved.path("id").asLong(), savedPayload(saved, status, ids), operator));
    }
    private JsonNode detail(String slug) throws Exception { return ok(send("GET", "/series/" + slug, null, null)); }
    private JsonNode nav(long articleId) throws Exception { return ok(send("GET", "/series/by-article/" + articleId, null, null)); }
    private String sitemap() throws Exception {
        HttpResponse<String> response = send("GET", "/sitemap.xml", null, null);
        assertEquals(200, response.statusCode()); return response.body();
    }
    private List<Long> articleIds(JsonNode detail) {
        List<Long> ids = new ArrayList<>(); detail.path("articles").forEach(a -> ids.add(a.path("id").asLong())); return ids;
    }
    private boolean containsId(JsonNode records, long id) { return findById(records, id) != null; }
    private JsonNode findById(JsonNode records, long id) {
        for (JsonNode row : records) if (row.path("id").asLong() == id) return row;
        return null;
    }
    private int code(HttpResponse<String> response) { return json.readTree(response.body()).path("code").asInt(); }
    private void assertCode(int expected, HttpResponse<String> response) {
        assertEquals(expected, code(response), response::body);
    }
    private JsonNode ok(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response::body); assertCode(200, response);
        return json.readTree(response.body()).path("data");
    }
    private HttpResponse<String> send(String method, String path, Object payload, Account account) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15))
                .header("X-Requested-With", "XMLHttpRequest");
        if (account != null) request.header("Authorization", "Bearer " + account.token());
        var body = payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload));
        if (payload != null) request.header("Content-Type", "application/json");
        return http.send(request.method(method, body).build(), HttpResponse.BodyHandlers.ofString());
    }
    private record Account(long id, String username, String token) { }
}
