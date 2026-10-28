package com.zer0drv.blog.realit;

import com.sun.net.httpserver.HttpServer;
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
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP and competing MySQL transactions; published rows and durable working copies stay separate. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.location=classpath:/application-real-it.yaml")
@ContextConfiguration(initializers = RealInfrastructureSafety.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArticleWorkingDraftRealIT {
    private static final String RUN = UUID.randomUUID().toString().replace("-", "");
    private static final String BUCKET = "blog-it-" + RUN;
    private static final HttpServer SHELL = frontendShell();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final List<Account> accounts = new ArrayList<>();
    private final Set<String> redisKeys = new HashSet<>();
    private final List<String> blockedObjects = new ArrayList<>();
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private ObjectMapper json;
    @Autowired private TokenService tokens;
    @Autowired private StringRedisTemplate redis;
    @Autowired private MinioClient minio;
    @Autowired private AttachmentReferenceSnapshot references;
    private Account author, other, admin, superAdmin, reader;
    private String prefix;
    private long categoryA, categoryB, tagA, tagB;

    @DynamicPropertySource
    static void isolate(DynamicPropertyRegistry registry) {
        registry.add("blog.totp.encryption-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        registry.add("blog.minio.bucket", () -> BUCKET);
        registry.add("blog.upload-dir", () -> Path.of("target", "real-it-uploads", RUN).toAbsolutePath().toString());
        registry.add("blog.seo.frontend-index-url", () -> "http://127.0.0.1:" + SHELL.getAddress().getPort() + "/index.html");
    }

    private static HttpServer frontendShell() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/index.html", exchange -> {
                byte[] html = "<!doctype html><html><head><title>Shell</title></head><body><div id=app></div></body></html>".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html;charset=UTF-8");
                exchange.sendResponseHeaders(200, html.length);
                try (var output = exchange.getResponseBody()) { output.write(html); }
            });
            server.start();
            return server;
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    @BeforeAll
    void requireRealInfrastructureAndMigration() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
        }
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='22' AND success=1", Long.class));
        assertTrue(minio.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build()));
    }

    @BeforeEach
    void seedOwnedFixtures() {
        prefix = "working-" + UUID.randomUUID().toString().replace("-", "");
        author = account("AUTHOR"); other = account("AUTHOR"); reader = account("USER");
        admin = account("ADMIN"); superAdmin = account("SUPER_ADMIN");
        jdbc.update("INSERT INTO category(name) VALUES(?),(?)", prefix + "-a", prefix + "-b");
        categoryA = jdbc.queryForObject("SELECT id FROM category WHERE name=?", Long.class, prefix + "-a");
        categoryB = jdbc.queryForObject("SELECT id FROM category WHERE name=?", Long.class, prefix + "-b");
        jdbc.update("INSERT INTO tag(name) VALUES(?),(?)", prefix + "-a", prefix + "-b");
        tagA = jdbc.queryForObject("SELECT id FROM tag WHERE name=?", Long.class, prefix + "-a");
        tagB = jdbc.queryForObject("SELECT id FROM tag WHERE name=?", Long.class, prefix + "-b");
    }

    @AfterEach
    void removeOnlyOwnedFixtures() {
        for (String key : blockedObjects) jdbc.update("DELETE FROM attachment_cleanup WHERE object_key=?", key);
        blockedObjects.clear();
        for (Account account : accounts) {
            jdbc.update("DELETE FROM article_series_item WHERE series_id IN (SELECT id FROM article_series WHERE owner_id=?)", account.id());
            jdbc.update("DELETE FROM article_series WHERE owner_id=?", account.id());
            for (String table : List.of("article_working_copy", "article_version", "article_tag", "subscription_delivery", "subscription_publication"))
                jdbc.update("DELETE FROM " + table + " WHERE article_id IN (SELECT id FROM article WHERE author_id=?)", account.id());
            jdbc.update("DELETE FROM article WHERE author_id=?", account.id());
            jdbc.update("DELETE FROM notification WHERE user_id=? OR actor_id=?", account.id(), account.id());
            jdbc.update("DELETE FROM `user` WHERE id=? AND username=?", account.id(), account.username());
            redisKeys.add("blog:user-tokens:" + account.id());
        }
        accounts.clear();
        jdbc.update("DELETE FROM tag WHERE id IN (?,?)", tagA, tagB);
        jdbc.update("DELETE FROM category WHERE id IN (?,?)", categoryA, categoryB);
        if (!redisKeys.isEmpty()) redis.delete(redisKeys);
        redisKeys.clear();
    }

    @AfterAll
    void closeOwnedResources() throws Exception {
        try { minio.removeBucket(RemoveBucketArgs.builder().bucket(BUCKET).build()); }
        finally { SHELL.stop(0); http.close(); }
    }

    @Test
    void durableWorkingCopyDoesNotLeakAndExplicitPublicationChangesAllPublicOutletsTogether() throws Exception {
        long id = create("PUBLISHED", "PUBLIC", "公开旧稿", "旧稿正文", null);
        JsonNode original = editing(id, author);
        String oldTitle = original.path("title").asString(), oldSlug = original.path("slug").asString();
        jdbc.update("UPDATE subscription_publication SET state='DONE' WHERE article_id=?", id);
        long seriesId = ok(send("POST", "/series/manage", Map.of("title", "工作稿验收系列", "slug", prefix + "-series",
                "description", "", "cover", "", "status", "PUBLISHED", "articleIds", List.of(id)), author)).path("id").asLong();
        Map<String, Object> changed = payload(original, "未发布新标题", "未发布新正文");
        changed.put("summary", "未发布摘要"); changed.put("slug", prefix + "-new");
        changed.put("categoryId", categoryB); changed.put("tagIds", List.of(tagB));
        JsonNode saved = ok(send("PUT", "/articles/" + id, changed, author));
        assertTrue(saved.path("hasWorkingCopy").asBoolean());
        assertEquals(oldTitle, saved.path("activeTitle").asString());
        assertEquals(1, saved.path("editRevision").asLong());
        Account relogged = new Account(author.id(), author.username(), tokens.generateToken(author.id(), List.of("ROLE_AUTHOR")));
        assertEquals("未发布新正文", editing(id, relogged).path("content").asString());
        assertPublicTitle(id, oldTitle, "未发布新标题");
        JsonNode mine = byId(ok(send("GET", "/articles/mine?size=100", null, author)).path("records"), id);
        assertEquals(oldTitle, mine.path("title").asString());
        assertTrue(mine.path("hasWorkingCopy").asBoolean());
        assertEquals(oldTitle, ok(send("GET", "/articles/slug/" + oldSlug, null, null)).path("title").asString());
        assertEquals(oldTitle, ok(send("GET", "/series/" + prefix + "-series", null, null)).path("articles").get(0).path("title").asString());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM article_series_item WHERE series_id=? AND article_id=?", Long.class, seriesId, id));
        assertEquals(0L, versionCount(id));
        assertEquals(List.of(tagA), activeTags(id));
        JsonNode published = ok(send("POST", "/articles/" + id + "/publish", payload(saved), author));
        assertFalse(published.path("hasWorkingCopy").asBoolean());
        assertEquals("未发布新标题", published.path("activeTitle").asString());
        assertPublicTitle(id, "未发布新标题", oldTitle);
        assertFalse(byId(ok(send("GET", "/articles/mine?size=100", null, author)).path("records"), id).path("hasWorkingCopy").asBoolean());
        assertEquals("未发布新正文", jdbc.queryForObject("SELECT content_text FROM article WHERE id=?", String.class, id));
        assertEquals(categoryB, jdbc.queryForObject("SELECT category_id FROM article WHERE id=?", Long.class, id));
        assertEquals(List.of(tagB), activeTags(id));
        assertEquals(1L, versionCount(id));
        assertEquals(oldTitle, jdbc.queryForObject("SELECT title FROM article_version WHERE article_id=? AND version=1", String.class, id));
        assertEquals("DONE", jdbc.queryForObject("SELECT state FROM subscription_publication WHERE article_id=?", String.class, id));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM subscription_publication WHERE article_id=?", Long.class, id));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM article_working_copy WHERE article_id=?", Long.class, id));
    }

    @Test
    void concurrentSaveAndPublishEachCommitExactlyOneCompleteRevision() throws Exception {
        long id = create("PUBLISHED", "PUBLIC", "并发原稿", "原稿", null);
        JsonNode original = editing(id, author);
        Map<String, Object> a = payload(original, "修改甲", "正文甲"), b = payload(original, "修改乙", "正文乙");
        a.put("tagIds", List.of(tagA)); a.put("categoryId", categoryA);
        b.put("tagIds", List.of(tagB)); b.put("categoryId", categoryB);
        var replies = compete("PUT", "/articles/" + id, a, b);
        assertEquals(List.of(200, 40901), replies.stream().map(this::code).sorted().toList());
        Map<String, Object> winner = code(replies.get(0)) == 200 ? a : b;
        JsonNode saved = editing(id, author);
        assertEquals(winner.get("title"), saved.path("title").asString());
        assertEquals(winner.get("content"), saved.path("content").asString());
        assertEquals(winner.get("tagIds"), tags(saved));
        assertEquals(original.path("title"), ok(send("GET", "/articles/" + id, null, null)).path("title"));
        assertEquals(0L, versionCount(id));
        var publicationReplies = compete("POST", "/articles/" + id + "/publish", payload(saved), payload(saved));
        assertEquals(List.of(200, 40901), publicationReplies.stream().map(this::code).sorted().toList());
        assertEquals(1L, versionCount(id));
        assertEquals(2, editing(id, author).path("editRevision").asLong());
        assertEquals(winner.get("title"), ok(send("GET", "/articles/" + id, null, null)).path("title").asString());
        assertEquals(winner.get("tagIds"), activeTags(id));
        assertCode(40901, send("PUT", "/articles/" + id + "/autosave", a, author));
        assertFalse(editing(id, author).path("hasWorkingCopy").asBoolean());
    }

    @Test
    void invalidPublicationAndMissingRevisionLeaveDraftPublishedRowTagsAndVersionsIntact() throws Exception {
        long id = create("PUBLISHED", "PUBLIC", "失败前原稿", "原稿", null);
        JsonNode original = editing(id, author);
        JsonNode saved = ok(send("PUT", "/articles/" + id, payload(original, "待发布工作稿", "工作稿正文"), author));
        long occupied = create("DRAFT", "PUBLIC", "占用链接", "占用", null);
        Map<String, Object> missingRevision = payload(saved); missingRevision.remove("editRevision");
        Map<String, Object> badTag = payload(saved); badTag.put("tagIds", List.of(Long.MAX_VALUE));
        Map<String, Object> duplicateSlug = payload(saved); duplicateSlug.put("slug", editing(occupied, author).path("slug").asString());
        for (var invalid : List.of(missingRevision, badTag, duplicateSlug)) {
            assertNotEquals(200, code(send("POST", "/articles/" + id + "/publish", invalid, author)));
            JsonNode kept = editing(id, author);
            assertEquals(saved.path("editRevision"), kept.path("editRevision"));
            assertEquals(saved.path("content"), kept.path("content"));
            assertTrue(kept.path("hasWorkingCopy").asBoolean());
            assertEquals(original.path("title"), ok(send("GET", "/articles/" + id, null, null)).path("title"));
            assertEquals(List.of(tagA), activeTags(id));
            assertEquals(0L, versionCount(id));
        }
    }

    @Test
    void mysqlFailureAfterPublicationWritesRollsBackSnapshotActiveRowTagsAndRevision() throws Exception {
        long id = create("PUBLISHED", "PUBLIC", "事务原稿", "事务原正文", null);
        JsonNode original = editing(id, author);
        Map<String, Object> change = payload(original, "待事务发布", "事务工作稿正文");
        change.put("tagIds", List.of(tagB)); change.put("categoryId", categoryB);
        JsonNode saved = ok(send("PUT", "/articles/" + id, change, author));
        String trigger = "working_fail_" + UUID.randomUUID().toString().replace("-", "");
        // A real MySQL failure after the active row and snapshot writes must roll the whole publication back.
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON article_tag FOR EACH ROW BEGIN IF NEW.article_id=" + id
                + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='owned working-copy rollback fixture'; END IF; END");
        try {
            assertNotEquals(200, code(send("POST", "/articles/" + id + "/publish", payload(saved), author)));
            assertEquals(original.path("title").asString(), jdbc.queryForObject("SELECT title FROM article WHERE id=?", String.class, id));
            assertEquals(categoryA, jdbc.queryForObject("SELECT category_id FROM article WHERE id=?", Long.class, id));
            assertEquals(List.of(tagA), activeTags(id));
            assertEquals(0L, versionCount(id));
            JsonNode kept = editing(id, author);
            assertEquals(saved.path("editRevision"), kept.path("editRevision"));
            assertEquals(saved.path("content"), kept.path("content"));
            assertTrue(kept.path("hasWorkingCopy").asBoolean());
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
        }
        JsonNode retried = ok(send("POST", "/articles/" + id + "/publish", payload(saved), author));
        assertEquals(2L, retried.path("editRevision").asLong());
        assertFalse(retried.path("hasWorkingCopy").asBoolean());
        assertEquals(List.of(tagB), activeTags(id));
        assertEquals(1L, versionCount(id));
    }

    @Test
    void workingCopyEndpointsRequireOwnerOrAdministratorEvenUnderPublicArticleRoute() throws Exception {
        long id = create("PUBLISHED", "PUBLIC", "权限原稿", "权限正文", null);
        JsonNode original = editing(id, author);
        assertNotEquals(200, code(send("GET", "/articles/" + id + "/editing", null, null)));
        assertNotEquals(200, code(send("GET", "/articles/" + id + "/editing", null, reader)));
        assertCode(40301, send("GET", "/articles/" + id + "/editing", null, other));
        assertCode(40301, send("PUT", "/articles/" + id, payload(original), other));
        assertCode(40301, send("PUT", "/articles/" + id + "/autosave", payload(original), other));
        assertCode(40301, send("POST", "/articles/" + id + "/publish", payload(original), other));
        assertCode(40301, send("DELETE", "/articles/" + id + "/working-copy?editRevision=0", null, other));
        JsonNode saved = ok(send("PUT", "/articles/" + id, payload(original, "管理员工作稿", "管理员正文"), admin));
        assertEquals("管理员正文", editing(id, superAdmin).path("content").asString());
        assertEquals(author.id(), editing(id, admin).path("author").path("id").asLong());
        ok(send("POST", "/articles/" + id + "/publish", payload(saved), superAdmin));
        assertCode(40301, send("POST", "/articles/" + id + "/restore/1?editRevision=2", null, other));
        assertEquals("管理员工作稿", ok(send("GET", "/articles/" + id, null, null)).path("title").asString());
    }

    @Test
    void passwordPrivateAndScheduledArticlesKeepTheirActiveAccessRulesUntilExplicitPublication() throws Exception {
        for (String visibility : List.of("PUBLIC", "PASSWORD", "PRIVATE")) {
            LocalDateTime scheduled = visibility.equals("PUBLIC") ? LocalDateTime.now().plusDays(1).withNano(0) : null;
            long id = create("PUBLISHED", visibility, "受限原稿", "受限正文", scheduled);
            JsonNode original = editing(id, author);
            String oldHash = jdbc.queryForObject("SELECT access_password_hash FROM article WHERE id=?", String.class, id);
            Map<String, Object> change = payload(original, "未公开修改", "未公开正文");
            if (visibility.equals("PASSWORD")) change.put("accessPassword", "new-password-93");
            if (scheduled != null) change.put("publishTime", scheduled.plusDays(1).toString());
            JsonNode saved = ok(send("PUT", "/articles/" + id, change, author));
            assertEquals(oldHash, jdbc.queryForObject("SELECT access_password_hash FROM article WHERE id=?", String.class, id));
            String oldGrant = null;
            if (visibility.equals("PASSWORD")) {
                JsonNode gate = ok(send("GET", "/articles/" + id, null, null));
                assertTrue(gate.path("passwordRequired").asBoolean());
                assertTrue(gate.path("content").isNull() || gate.path("content").isMissingNode());
                oldGrant = passwordGrant(id, "old-password-93");
                assertEquals(original.path("content"), ok(send("GET", "/articles/" + id, null, null, oldGrant)).path("content"));
            } else assertCode(40030, send("GET", "/articles/" + id, null, null));
            JsonNode published = ok(send("POST", "/articles/" + id + "/publish", payload(saved), author));
            assertFalse(published.path("hasWorkingCopy").asBoolean());
            if (scheduled != null) {
                assertEquals(scheduled, jdbc.queryForObject("SELECT publish_time FROM article WHERE id=?", LocalDateTime.class, id));
                assertCode(40030, send("GET", "/articles/" + id, null, null));
            }
            if (visibility.equals("PASSWORD")) {
                assertNotEquals(oldHash, jdbc.queryForObject("SELECT access_password_hash FROM article WHERE id=?", String.class, id));
                assertTrue(ok(send("GET", "/articles/" + id, null, null, oldGrant)).path("passwordRequired").asBoolean());
                String newGrant = passwordGrant(id, "new-password-93");
                assertEquals("未公开正文", ok(send("GET", "/articles/" + id, null, null, newGrant)).path("content").asString());
            }
        }
    }

    @Test
    void emptyAutosavesAndStatusChangesCannotSilentlyPublishAWorkingCopy() throws Exception {
        long id = create("DRAFT", "PUBLIC", "草稿原文", "草稿正文", null);
        JsonNode original = editing(id, author);
        JsonNode empty = ok(send("PUT", "/articles/" + id + "/autosave", payload(original, "", ""), author));
        assertTrue(empty.path("hasWorkingCopy").asBoolean());
        assertEquals("", editing(id, author).path("content").asString());
        assertEquals(original.path("title").asString(), jdbc.queryForObject("SELECT title FROM article WHERE id=?", String.class, id));
        assertNotEquals(200, code(send("PUT", "/articles/" + id + "/status", Map.of("status", "PUBLISHED"), author)));
        assertNotEquals(200, code(send("POST", "/articles/" + id + "/publish", payload(empty), author)));
        JsonNode discarded = ok(send("DELETE", "/articles/" + id + "/working-copy?editRevision=" + empty.path("editRevision").asLong(), null, author));
        assertFalse(discarded.path("hasWorkingCopy").asBoolean());
        JsonNode draftSaved = ok(send("PUT", "/articles/" + id, payload(discarded, "新草稿", "新草稿正文"), author));
        assertFalse(draftSaved.path("hasWorkingCopy").asBoolean());
        assertEquals("新草稿", jdbc.queryForObject("SELECT title FROM article WHERE id=?", String.class, id));
        ok(send("PUT", "/articles/" + id + "/status", Map.of("status", "OFFLINE"), author));
        assertCode(40901, send("PUT", "/articles/" + id, payload(draftSaved), author));
        JsonNode offline = editing(id, author);
        JsonNode offlineSaved = ok(send("PUT", "/articles/" + id, payload(offline, "下架修订", "下架修订正文"), author));
        assertFalse(offlineSaved.path("hasWorkingCopy").asBoolean());
        assertCode(40030, send("GET", "/articles/" + id, null, null));
    }

    @Test
    void restoreVersionStagesPublishedContentAndDiscardKeepsTheCurrentPublication() throws Exception {
        long id = create("PUBLISHED", "PUBLIC", "历史原稿", "历史正文", null);
        JsonNode original = editing(id, author);
        JsonNode updated = ok(send("POST", "/articles/" + id + "/publish", payload(original, "当前发布稿", "当前正文"), author));
        assertEquals(1L, versionCount(id));
        JsonNode restored = ok(send("POST", "/articles/" + id + "/restore/1?editRevision=" + updated.path("editRevision").asLong(), null, author));
        assertEquals(original.path("title"), restored.path("title"));
        assertTrue(restored.path("hasWorkingCopy").asBoolean());
        assertEquals("当前发布稿", ok(send("GET", "/articles/" + id, null, null)).path("title").asString());
        assertEquals(1L, versionCount(id));
        assertCode(40901, send("DELETE", "/articles/" + id + "/working-copy?editRevision=" + updated.path("editRevision").asLong(), null, author));
        JsonNode discarded = ok(send("DELETE", "/articles/" + id + "/working-copy?editRevision=" + restored.path("editRevision").asLong(), null, author));
        assertEquals("当前发布稿", discarded.path("title").asString());
        JsonNode again = ok(send("POST", "/articles/" + id + "/restore/1?editRevision=" + discarded.path("editRevision").asLong(), null, author));
        ok(send("POST", "/articles/" + id + "/publish", payload(again), author));
        assertEquals(original.path("title"), ok(send("GET", "/articles/" + id, null, null)).path("title"));
        assertEquals(2L, versionCount(id));
    }

    @Test
    void legacyRedisDraftIsOnlyAnExplicitRecoverySourceAndStaleAutosaveCannotReviveDiscardedChanges() throws Exception {
        long id = create("PUBLISHED", "PUBLIC", "线上原稿", "线上正文", null);
        String legacyKey = "blog:autosave:" + id + ":" + author.id();
        redisKeys.add(legacyKey);
        redis.opsForValue().set(legacyKey, json.writeValueAsString(Map.of("title", "旧Redis草稿", "content", "旧缓存正文", "savedAt", System.currentTimeMillis())), Duration.ofHours(2));
        JsonNode original = editing(id, author);
        assertNotEquals("旧Redis草稿", original.path("title").asString());
        assertEquals("旧Redis草稿", ok(send("GET", "/articles/" + id + "/autosave", null, author)).path("title").asString());
        JsonNode saved = ok(send("PUT", "/articles/" + id + "/autosave", payload(original, "持久修改", "持久正文"), author));
        // Even an old tab reintroducing the legacy key cannot make GET editing import it.
        redis.opsForValue().set(legacyKey, json.writeValueAsString(Map.of("title", "更旧缓存", "content", "旧正文", "savedAt", System.currentTimeMillis())), Duration.ofHours(2));
        assertEquals("持久修改", editing(id, author).path("title").asString());
        JsonNode discarded = ok(send("DELETE", "/articles/" + id + "/working-copy?editRevision=" + saved.path("editRevision").asLong(), null, author));
        assertCode(40901, send("PUT", "/articles/" + id + "/autosave", payload(saved), author));
        assertEquals(discarded.path("editRevision"), editing(id, author).path("editRevision"));
        assertFalse(editing(id, author).path("hasWorkingCopy").asBoolean());
        Map<String, Object> unversioned = payload(discarded); unversioned.remove("editRevision");
        assertNotEquals(200, code(send("PUT", "/articles/" + id + "/autosave", unversioned, author)));
    }

    @Test
    void workingCopyAttachmentsRemainReferencedUntilDiscardOrPhysicalDeletion() throws Exception {
        long id = create("PUBLISHED", "PUBLIC", "附件原稿", "无附件正文", null);
        String filename = UUID.randomUUID().toString().replace("-", "") + ".png";
        String url = "http://127.0.0.1:29000/" + BUCKET + "/" + filename;
        JsonNode original = editing(id, author);
        Map<String, Object> body = payload(original, "附件工作稿", "![工作稿图片](" + url + ")"); body.put("cover", url);
        JsonNode saved = ok(send("PUT", "/articles/" + id, body, author));
        assertTrue(references.load().contains(filename));
        JsonNode discarded = ok(send("DELETE", "/articles/" + id + "/working-copy?editRevision=" + saved.path("editRevision").asLong(), null, author));
        assertFalse(references.load().contains(filename));
        String blocked = "202610/" + UUID.randomUUID().toString().replace("-", "") + ".png";
        blockedObjects.add(blocked);
        jdbc.update("INSERT INTO attachment_cleanup(storage,object_key,size_bytes,fingerprint,state,marked_at,eligible_at,operator_id) VALUES('MINIO',?,1,'working-draft-fixture','PURGING',NOW(),NOW(),?)", blocked, author.id());
        Map<String, Object> invalid = payload(discarded); invalid.put("cover", "/uploads/" + blocked);
        assertCode(40023, send("PUT", "/articles/" + id + "/autosave", invalid, author));
        body.put("editRevision", discarded.path("editRevision").asLong());
        saved = ok(send("PUT", "/articles/" + id, body, author));
        ok(send("DELETE", "/articles/" + id, null, author));
        assertTrue(references.load().contains(filename));
        assertCode(40030, send("GET", "/articles/" + id + "/editing", null, author));
        ok(send("DELETE", "/articles/" + id + "/force", null, author));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM article_working_copy WHERE article_id=?", Long.class, id));
        assertFalse(references.load().contains(filename));
    }

    private void assertPublicTitle(long id, String expected, String hidden) throws Exception {
        JsonNode detail = ok(send("GET", "/articles/" + id, null, null));
        assertEquals(expected, detail.path("title").asString());
        JsonNode listed = byId(ok(send("GET", "/articles?size=100", null, null)).path("records"), id);
        assertEquals(expected, listed.path("title").asString());
        assertTrue(listed.path("hasWorkingCopy").isNull() || listed.path("hasWorkingCopy").isMissingNode());
        String slug = detail.path("slug").asString();
        JsonNode search = ok(send("GET", "/articles/search?keyword=" + URLEncoder.encode(expected, StandardCharsets.UTF_8), null, null));
        assertTrue(iterable(search.path("records")).stream().anyMatch(row -> row.path("id").asLong() == id && expected.equals(row.path("title").asString())));
        for (String path : List.of("/rss.xml", "/atom.xml", "/article/" + slug)) {
            HttpResponse<String> response = send("GET", path, null, null);
            assertEquals(200, response.statusCode(), response::body);
            assertTrue(response.body().contains(expected), path + " must use the active title");
            assertFalse(response.body().contains(hidden), path + " must not expose the other draft");
        }
        assertTrue(send("GET", "/sitemap.xml", null, null).body().contains("/article/" + slug + "</loc>"));
    }

    private List<HttpResponse<String>> compete(String method, String path, Object a, Object b) throws Exception {
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> { ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS)); return send(method, path, a, author); });
            var two = pool.submit(() -> { ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS)); return send(method, path, b, author); });
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            return List.of(one.get(25, TimeUnit.SECONDS), two.get(25, TimeUnit.SECONDS));
        }
    }

    private Account account(String role) {
        String username = "wrk_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO `user`(username,email,nickname,role,status) VALUES(?,?,'Working Draft IT',?,0)", username, username + "@real-it.invalid", role);
        long id = jdbc.queryForObject("SELECT id FROM `user` WHERE username=?", Long.class, username);
        Account account = new Account(id, username, tokens.generateToken(id, List.of("ROLE_" + role)));
        accounts.add(account); return account;
    }

    private long create(String status, String visibility, String title, String content, LocalDateTime schedule) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("title", title + " " + prefix, "content", content, "editorType", "MARKDOWN",
                "summary", "原摘要", "slug", prefix + "-" + UUID.randomUUID().toString().substring(0, 8), "cover", "", "status", status, "visibility", visibility));
        body.put("categoryId", categoryA); body.put("tagIds", List.of(tagA));
        if (visibility.equals("PASSWORD")) body.put("accessPassword", "old-password-93");
        if (schedule != null) body.put("publishTime", schedule.toString());
        return ok(send("POST", "/articles", body, author)).asLong();
    }

    private Map<String, Object> payload(JsonNode value) { return payload(value, value.path("title").asString(), value.path("content").asString()); }
    private Map<String, Object> payload(JsonNode value, String title, String content) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (String field : List.of("slug", "summary", "cover", "editorType", "status", "visibility")) body.put(field, value.path(field).asString());
        body.put("title", title); body.put("content", content); body.put("editRevision", value.path("editRevision").asLong());
        body.put("categoryId", value.path("categoryId").isNull() || value.path("categoryId").isMissingNode() ? null : value.path("categoryId").asLong());
        body.put("tagIds", tags(value));
        if (!value.path("publishTime").isNull() && !value.path("publishTime").isMissingNode()) body.put("publishTime", value.path("publishTime").asString());
        return body;
    }
    private JsonNode editing(long id, Account account) throws Exception { return ok(send("GET", "/articles/" + id + "/editing", null, account)); }
    private List<Long> tags(JsonNode value) { return iterable(value.path("tags")).stream().map(tag -> tag.path("id").asLong()).sorted().toList(); }
    private List<JsonNode> iterable(JsonNode node) { List<JsonNode> result = new ArrayList<>(); node.forEach(result::add); return result; }
    private JsonNode byId(JsonNode nodes, long id) { return iterable(nodes).stream().filter(node -> node.path("id").asLong() == id).findFirst().orElseThrow(); }
    private List<Long> activeTags(long id) { return jdbc.queryForList("SELECT tag_id FROM article_tag WHERE article_id=? ORDER BY tag_id", Long.class, id); }
    private long versionCount(long id) { return jdbc.queryForObject("SELECT COUNT(*) FROM article_version WHERE article_id=?", Long.class, id); }
    private int code(HttpResponse<String> response) { return json.readTree(response.body()).path("code").asInt(); }
    private void assertCode(int expected, HttpResponse<String> response) { assertEquals(expected, code(response), response::body); }
    private String passwordGrant(long id, String password) throws Exception {
        String token = ok(send("POST", "/articles/" + id + "/password", Map.of("password", password), null)).path("accessToken").asString();
        redisKeys.add("blog:article-access:" + id + ":" + token);
        return token;
    }
    private JsonNode ok(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response::body); assertCode(200, response);
        return json.readTree(response.body()).path("data");
    }
    private HttpResponse<String> send(String method, String path, Object payload, Account account) throws Exception {
        return send(method, path, payload, account, null);
    }
    private HttpResponse<String> send(String method, String path, Object payload, Account account, String articleGrant) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20)).header("X-Requested-With", "XMLHttpRequest");
        if (account != null) request.header("Authorization", "Bearer " + account.token());
        if (articleGrant != null) request.header("X-Article-Access-Token", articleGrant);
        var body = payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload));
        if (payload != null) request.header("Content-Type", "application/json");
        return http.send(request.method(method, body).build(), HttpResponse.BodyHandlers.ofString());
    }
    private record Account(long id, String username, String token) { }
}
