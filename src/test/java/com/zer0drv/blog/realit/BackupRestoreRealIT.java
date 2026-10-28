package com.zer0drv.blog.realit;

import com.zer0drv.blog.BlogApplication;
import io.minio.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Actual SQL dump/reload and attachment recovery after deleting only this run's generated sources. */
class BackupRestoreRealIT {
    private static final String JDBC = "jdbc:mysql://127.0.0.1:23307/blog_it?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8";
    private static final String PASSWORD = "Restore-drill-passw0rd!";
    private static final String MINIO = "http://127.0.0.1:29000";
    private static final String LOCAL_KEY = "202610/" + "a".repeat(32) + ".png";
    private static final String MINIO_KEY = "202610/" + "b".repeat(32) + ".png";
    private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jU1sAAAAASUVORK5CYII=");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void recoverDatabaseLocalAndMinioAttachmentsFromVerifiedSnapshot() throws Exception {
        assertEquals("true", System.getProperty("blog.real-it.run"));
        RealInfrastructureSafety.mysql(JDBC); // No host / production credential overrides for this drill.
        String run = UUID.randomUUID().toString().replace("-", "");
        String source = RestoreDrillSnapshot.schema("source", run);
        String restored = RestoreDrillSnapshot.schema("restore", run);
        String bucket = "blog-it-" + run;
        Path root = Path.of("target", "restore-drill", run).toAbsolutePath().normalize();
        Path backup = Files.createDirectories(root.resolve("snapshot"));
        Path sourceUploads = root.resolve("source-uploads");
        Path restoredUploads = root.resolve("restored-uploads");
        Path evidence = Path.of("target", "real-it-reports", "restore-drill.json");
        Files.createDirectories(evidence.getParent());
        Files.deleteIfExists(evidence); // An old successful report must not survive a failed new attempt.
        Files.writeString(root.resolve("empty.env"), "# Isolated restore drill: never load the host .env\n");
        var admin = jdbc(JDBC);
        var minio = MinioClient.builder().endpoint(MINIO).credentials("blog-it-user", "blog-it-minio-password").build();
        var report = new LinkedHashMap<String, Object>();
        report.put("runId", run);
        report.put("startedAt", Instant.now().toString());
        report.put("scope", "synthetic isolated MySQL 8.4 + current schema + local/MinIO objects; not a production restore");
        report.put("productionBackupPolicy", "待确认");
        report.put("rpoTarget", "待确认");
        report.put("rtoTarget", "待确认");
        boolean sourceCreated = false, restoredCreated = false, bucketCreated = false;
        try {
            admin.execute("CREATE DATABASE `" + source + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            sourceCreated = true;
            String sourceUrl = url(source);
            var flyway = migrations(sourceUrl);
            flyway.migrate();
            var db = jdbc(sourceUrl);
            String minioUrl = MINIO + "/" + bucket + "/" + MINIO_KEY;
            String content = "# 恢复演练 🧪\n\n![本地](/uploads/" + LOCAL_KEY + ")\n![对象](" + minioUrl + ")";
            seed(db, content, minioUrl);
            Files.createDirectories(sourceUploads.resolve(LOCAL_KEY).getParent());
            Files.write(sourceUploads.resolve(LOCAL_KEY), PNG);
            minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            bucketCreated = true;
            put(minio, bucket, PNG);
            var before = inventory(db);
            report.put("mysqlVersion", db.queryForObject("SELECT VERSION()", String.class));
            report.put("migration", flyway.info().current().getVersion().toString());

            // The application is not running and the generated source is quiescent across all three backups.
            long backupStart = System.nanoTime();
            cli(root, List.of("mysqldump", "--user=root", "--default-character-set=utf8mb4", "--single-transaction",
                    "--quick", "--routines", "--events", "--triggers", "--no-tablespaces", "--set-gtid-purged=OFF", source),
                    null, backup.resolve("database.sql"), "dump");
            Files.copy(sourceUploads.resolve(LOCAL_KEY), backup.resolve("local.png"));
            try (var input = minio.getObject(GetObjectArgs.builder().bucket(bucket).object(MINIO_KEY).build())) {
                Files.copy(input, backup.resolve("minio.png"));
            }
            var manifest = RestoreDrillSnapshot.manifest(backup);
            Files.writeString(backup.resolve("manifest.json"), mapper.writeValueAsString(manifest));
            // Reload persisted evidence rather than trusting only the in-memory map.
            var persisted = mapper.readValue(Files.readString(backup.resolve("manifest.json")),
                    new tools.jackson.core.type.TypeReference<Map<String, String>>() {});
            RestoreDrillSnapshot.verify(backup, persisted);
            report.put("backupMillis", elapsed(backupStart));
            report.put("snapshotSha256", persisted);
            report.put("snapshotBytes", Files.size(backup.resolve("database.sql")) + PNG.length * 2L);

            // Prove that no live source remains available to accidentally satisfy recovery assertions.
            admin.execute("DROP DATABASE `" + source + "`");
            sourceCreated = false;
            minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(MINIO_KEY).build());
            minio.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());
            bucketCreated = false;
            Files.delete(sourceUploads.resolve(LOCAL_KEY));
            assertEquals(0, admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, source));
            assertFalse(minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()));
            assertFalse(Files.exists(sourceUploads.resolve(LOCAL_KEY)));
            report.put("sourceRemovedBeforeRestore", true);

            long restoreStart = System.nanoTime();
            RestoreDrillSnapshot.verify(backup, persisted); // ALL files pass before creating any recovery target.
            admin.execute("CREATE DATABASE `" + restored + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            restoredCreated = true;
            cli(root, List.of("mysql", "--user=root", "--default-character-set=utf8mb4", "--database=" + restored),
                    backup.resolve("database.sql"), root.resolve("reload.log"), "reload");
            Files.createDirectories(restoredUploads.resolve(LOCAL_KEY).getParent());
            Files.copy(backup.resolve("local.png"), restoredUploads.resolve(LOCAL_KEY));
            minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            bucketCreated = true;
            put(minio, bucket, Files.readAllBytes(backup.resolve("minio.png")));
            report.put("restoreDataMillis", elapsed(restoreStart));

            var recovered = jdbc(url(restored));
            assertEquals(before, inventory(recovered), "Every table's complete logical rows must survive the SQL dump");
            var restoredFlyway = migrations(url(restored));
            assertTrue(restoredFlyway.validateWithResult().validationSuccessful);
            assertEquals(0, restoredFlyway.migrate().migrationsExecuted);
            verifyRows(recovered, content, minioUrl);
            assertArrayEquals(PNG, Files.readAllBytes(restoredUploads.resolve(LOCAL_KEY)));
            try (var input = minio.getObject(GetObjectArgs.builder().bucket(bucket).object(MINIO_KEY).build())) {
                assertArrayEquals(PNG, input.readAllBytes());
            }
            verifyApplication(run, restored, restoredUploads, content, minioUrl);
            report.put("restoreAndVerifyMillis", elapsed(restoreStart));
            report.put("tables", before);
            report.put("checks", List.of("all table row counts + logical SHA-256", "BCrypt and real HTTP login", "article/category/tag/version/working copy/revision/trash/page/comment/subscription/config audit",
                    "local and MinIO attachment references + SHA-256 + anonymous HTTP bytes", "site config", "Flyway validate + migrate no-op",
                    "real public article detail after application restart"));
            report.put("result", "passed");
        } catch (Exception | AssertionError failure) {
            report.put("result", "failed");
            report.put("failureType", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            try {
                // Every destructive identifier is derived from this invocation's generated UUID, never read from a backup.
                if (sourceCreated) admin.execute("DROP DATABASE `" + RestoreDrillSnapshot.schema("source", run) + "`");
                if (restoredCreated) admin.execute("DROP DATABASE `" + RestoreDrillSnapshot.schema("restore", run) + "`");
                if (bucketCreated) {
                    for (var item : minio.listObjects(ListObjectsArgs.builder().bucket(bucket).recursive(true).build()))
                        minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(item.get().objectName()).build());
                    minio.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());
                }
                report.put("ownedDatabaseAndBucketCleanup", "passed");
            } catch (Exception cleanupFailure) {
                report.put("result", "failed");
                report.put("ownedDatabaseAndBucketCleanup", "failed");
                throw cleanupFailure;
            } finally {
                report.put("finishedAt", Instant.now().toString());
                Files.writeString(evidence, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            }
        }
    }

    private void seed(JdbcTemplate db, String content, String minioUrl) {
        String hash = new BCryptPasswordEncoder().encode(PASSWORD);
        db.update("INSERT INTO `user`(id,username,password,email,nickname,role) VALUES(800001,'restore_author',?,'restore@real-it.invalid','恢复作者','AUTHOR')", hash);
        db.update("INSERT INTO category(id,name) VALUES(800003,'恢复分类')");
        db.update("INSERT INTO tag(id,name) VALUES(800004,'恢复标签')");
        db.update("INSERT INTO article(id,title,slug,content,content_text,editor_type,author_id,category_id,status,visibility,publish_time) VALUES(800002,'恢复正文 🧪','restore-public',?,?,'MARKDOWN',800001,800003,'PUBLISHED','PUBLIC',UTC_TIMESTAMP())", content, content);
        db.update("INSERT INTO article_tag(article_id,tag_id) VALUES(800002,800004)");
        db.update("INSERT INTO article_version(article_id,version,title,content,editor_type) VALUES(800002,2,'恢复历史',?,'MARKDOWN')", content);
        db.update("UPDATE article SET edit_revision=7 WHERE id=800002");
        db.update("INSERT INTO article_working_copy(article_id,title,slug,summary,content,editor_type,cover,category_id,tag_ids,visibility,publish_time) VALUES(800002,'恢复未发布工作稿','restore-working','尚未发布',?,'MARKDOWN',?,800003,'[800004]','PUBLIC',UTC_TIMESTAMP())", content + "\n未发布修改", minioUrl);
        db.update("INSERT INTO article(id,title,slug,content,editor_type,author_id,deleted) VALUES(800007,'回收站','restore-trash',?,'MARKDOWN',800001,1)", content);
        for (String storage : List.of("LOCAL", "MINIO")) {
            boolean local = storage.equals("LOCAL");
            db.update("INSERT INTO attachment(user_id,url,object_key,storage,filename,media_type,size_bytes) VALUES(800001,?,?,?,'恢复.png','image/png',?)",
                    local ? "/uploads/" + LOCAL_KEY : minioUrl, local ? LOCAL_KEY : MINIO_KEY, storage, PNG.length);
        }
        db.update("UPDATE site_config SET config_value='恢复站点 🧪' WHERE config_key='site.name'");
        db.update("INSERT INTO custom_page(slug,title,content,status) VALUES('restore-page','恢复页面',?,'PUBLISHED')", content);
        db.update("INSERT INTO comment(article_id,user_id,content,deleted) VALUES(800002,800001,'恢复评论',1)");
        db.update("INSERT INTO sys_config(config_key,config_value,value_type,group_name,description,validation_rule,hot_reload) VALUES('blog.upload-daily-limit','42','INT','上传','演练配额','{}',true)");
        db.update("INSERT INTO sys_config_audit(config_key,action,old_value,new_value,old_source,new_source,operator_id) VALUES('blog.upload-daily-limit','SET','100','42','YAML','DATABASE',800001)");
        db.update("INSERT INTO subscription_publication(article_id,due_at,state) VALUES(800002,UTC_TIMESTAMP(),'DONE')");
        db.update("INSERT INTO subscription_delivery(article_id,user_id,state,mail_state,attempts) VALUES(800002,800001,'DONE','SENT',1)");
    }

    private void verifyRows(JdbcTemplate db, String content, String minioUrl) {
        assertTrue(new BCryptPasswordEncoder().matches(PASSWORD, db.queryForObject("SELECT password FROM `user` WHERE id=800001", String.class)));
        assertEquals(content, db.queryForObject("SELECT content FROM article WHERE id=800002 AND category_id=800003 AND status='PUBLISHED' AND visibility='PUBLIC'", String.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM article_tag atg JOIN tag t ON t.id=atg.tag_id WHERE atg.article_id=800002 AND t.name='恢复标签'", Integer.class));
        assertEquals(content, db.queryForObject("SELECT content FROM article_version WHERE article_id=800002 AND version=2", String.class));
        assertEquals(7L, db.queryForObject("SELECT edit_revision FROM article WHERE id=800002", Long.class));
        assertEquals(content + "\n未发布修改", db.queryForObject("SELECT content FROM article_working_copy WHERE article_id=800002", String.class));
        assertEquals("[800004]", db.queryForObject("SELECT tag_ids FROM article_working_copy WHERE article_id=800002", String.class));
        assertEquals(1, db.queryForObject("SELECT deleted FROM article WHERE id=800007", Integer.class));
        assertEquals("恢复站点 🧪", db.queryForObject("SELECT config_value FROM site_config WHERE config_key='site.name'", String.class));
        assertEquals(minioUrl, db.queryForObject("SELECT url FROM attachment WHERE object_key=? AND storage='MINIO'", String.class, MINIO_KEY));
        assertEquals("/uploads/" + LOCAL_KEY, db.queryForObject("SELECT url FROM attachment WHERE object_key=? AND storage='LOCAL'", String.class, LOCAL_KEY));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM article WHERE id=800002 AND MATCH(title,content_text) AGAINST('恢复' IN NATURAL LANGUAGE MODE)", Integer.class),
                "Restored FULLTEXT index must execute real MySQL search");
    }

    private void verifyApplication(String run, String schema, Path uploads, String content, String minioUrl) throws Exception {
        var settings = new ArrayList<>(Arrays.asList(BrowserE2EServer.settings(run)));
        settings.removeIf(value -> value.startsWith("--server.port=") || value.startsWith("--spring.datasource.url=") || value.startsWith("--blog.upload-dir="));
        settings.addAll(List.of("--server.port=0", "--spring.datasource.url=" + url(schema), "--blog.upload-dir=" + uploads,
                "--spring.flyway.url=" + url(schema), "--spring.flyway.user=root", "--spring.flyway.password=blog-it-password",
                "--spring.data.redis.url=redis://127.0.0.1:26379/15"));
        var app = new SpringApplication(BlogApplication.class);
        app.addInitializers(new RealInfrastructureSafety());
        try (var context = app.run(settings.toArray(String[]::new)); var http = HttpClient.newHttpClient()) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            String origin = "http://127.0.0.1:" + port;
            var login = http.send(HttpRequest.newBuilder(URI.create(origin + "/auth/login"))
                    .header("Content-Type", "application/json").header("X-Requested-With", "XMLHttpRequest")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("username", "restore_author", "password", PASSWORD))))
                    .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, login.statusCode());
            assertEquals(200, mapper.readTree(login.body()).path("code").asInt(-1), login.body());
            assertTrue(login.headers().allValues("set-cookie").stream().anyMatch(value -> value.startsWith("AUTH_TOKEN=")));
            String authCookie = login.headers().allValues("set-cookie").stream().filter(value -> value.startsWith("AUTH_TOKEN=")).findFirst().orElseThrow().split(";", 2)[0];
            var editing = http.send(HttpRequest.newBuilder(URI.create(origin + "/articles/800002/editing")).header("Cookie", authCookie)
                    .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, editing.statusCode());
            var workingCopy = mapper.readTree(editing.body()).path("data");
            assertEquals(7L, workingCopy.path("editRevision").asLong());
            assertTrue(workingCopy.path("hasWorkingCopy").asBoolean());
            assertEquals(content + "\n未发布修改", workingCopy.path("content").asString());
            var article = http.send(HttpRequest.newBuilder(URI.create(origin + "/articles/800002")).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, article.statusCode());
            assertEquals(content, mapper.readTree(article.body()).path("data").path("content").asText(), article.body());
            for (String image : List.of(origin + "/uploads/" + LOCAL_KEY, minioUrl)) {
                var response = http.send(HttpRequest.newBuilder(URI.create(image)).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, response.statusCode(), image);
                assertArrayEquals(PNG, response.body(), image);
            }
        }
    }

    private Map<String, Object> inventory(JdbcTemplate db) throws Exception {
        var tables = new TreeMap<String, Object>();
        for (String table : db.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'", String.class)) {
            if (!table.matches("[a-zA-Z0-9_]+")) throw new IllegalStateException("Unsafe table identifier");
            // String values preserve SQL NULL, UTF-8 and timestamps; sorting makes row order immaterial.
            List<String> rows = db.query("SELECT * FROM `" + table + "`", (rs, rowNumber) -> {
                var values = new ArrayList<String>();
                for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++) values.add(rs.getString(column));
                return mapper.writeValueAsString(values);
            });
            rows.sort(Comparator.naturalOrder());
            String canonical = mapper.writeValueAsString(rows);
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
            tables.put(table, Map.of("rows", rows.size(), "logicalSha256", digest));
        }
        return tables;
    }

    private static JdbcTemplate jdbc(String url) { return new JdbcTemplate(new DriverManagerDataSource(url, "root", "blog-it-password")); }
    private static Flyway migrations(String url) { return Flyway.configure().dataSource(url, "root", "blog-it-password").locations("classpath:db/migration").cleanDisabled(true).load(); }
    private static String url(String schema) { return JDBC.replace("/blog_it?", "/" + schema + "?"); }
    private static long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    private static void put(MinioClient minio, String bucket, byte[] bytes) throws Exception {
        minio.putObject(PutObjectArgs.builder().bucket(bucket).object(MINIO_KEY).contentType("image/png")
                .stream(new ByteArrayInputStream(bytes), (long) bytes.length, -1L).build());
    }

    private static void cli(Path root, List<String> args, Path input, Path output, String name) throws Exception {
        // Explicit fixture project, compose file and empty env; no shell, default compose or production container names.
        var command = new ArrayList<>(List.of("docker", "compose", "--env-file", root.resolve("empty.env").toString(),
                "-p", "blog-real-it", "-f", "compose.real-it.yaml", "exec", "-T", "-e", "MYSQL_PWD=blog-it-password", "mysql"));
        command.addAll(args);
        Path diagnostic = Path.of("target", "real-it-reports", "restore-" + name + "-stderr.log");
        var builder = new ProcessBuilder(command).redirectOutput(output.toFile()).redirectError(diagnostic.toFile());
        if (input != null) builder.redirectInput(input.toFile());
        var process = builder.start();
        process.getOutputStream().close();
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Fixture MySQL CLI timed out: " + name);
        }
        assertEquals(0, process.exitValue(), "Fixture MySQL CLI failed; see " + diagnostic);
    }
}
