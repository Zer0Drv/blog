package com.zer0drv.blog.realit;

import com.zer0drv.blog.BlogApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test-only launcher; never packaged in the production application. No test HTTP endpoints. */
public final class BrowserE2EServer {
    public static final String PASSWORD = "Browser-e2e-passw0rd!";

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("Browser E2E does not accept configuration arguments");
        String run = System.getenv("BROWSER_E2E_RUN");
        String[] settings = settings(run);
        var app = new SpringApplication(BlogApplication.class);
        app.addInitializers(new RealInfrastructureSafety());
        var context = app.run(settings);
        var jdbc = context.getBean(JdbcTemplate.class);
        var encoder = context.getBean(PasswordEncoder.class);
        var accounts = new LinkedHashMap<String, String>();
        var ids = new LinkedHashMap<String, Long>();
        for (String role : List.of("ADMIN", "AUTHOR", "USER")) {
            String username = "be2e_" + run.substring(0, 12) + "_" + role.toLowerCase();
            jdbc.update("INSERT INTO `user`(username,password,email,nickname,role,status) VALUES(?,?,?,?,?,0)",
                    username, encoder.encode(PASSWORD), username + "@browser-e2e.invalid", "Browser " + role, role);
            accounts.put(role.toLowerCase(), username);
            ids.put(role, jdbc.queryForObject("SELECT id FROM `user` WHERE username=?", Long.class, username));
        }
        var articles = new LinkedHashMap<String, Long>();
        for (String name : List.of("public", "draft-a", "draft-b", "draft-failure")) {
            String status = name.equals("public") ? "PUBLISHED" : "DRAFT";
            String slug = "e2e-" + run + "-" + name;
            jdbc.update("""
                    INSERT INTO article(author_id,title,slug,content,content_text,summary,editor_type,status,
                                        visibility,publish_time)
                    VALUES(?,?,?,?,?,?,'MARKDOWN',?,'PUBLIC',UTC_TIMESTAMP())
                    """, ids.get("AUTHOR"), "E2E " + name + " " + run,
                    slug, "# Browser fixture\n\nIsolated browser data " + run,
                    "Browser fixture isolated browser data " + run, "Isolated browser fixture", status);
            articles.put(name, jdbc.queryForObject("SELECT id FROM article WHERE slug=?", Long.class, slug));
        }
        var evidence = Map.of("runId", run, "accounts", accounts, "articles", articles,
                "bucket", "blog-it-" + run, "backend", "http://127.0.0.1:28082");
        Path output = Path.of("target", "browser-e2e", "fixture.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, context.getBean(ObjectMapper.class).writeValueAsString(evidence));
        System.out.println("BROWSER_E2E_READY " + run);
    }

    static String[] settings(String run) {
        if (run == null || !run.matches("[a-f0-9]{32}"))
            throw new IllegalArgumentException("BROWSER_E2E_RUN must be a generated 32-character hex ID");
        // CLI properties outrank any host production environment variables.
        return new String[]{
                "--spring.config.location=classpath:/application-real-it.yaml", "--spring.profiles.active=browser-e2e",
                "--blog.real-it.run=true", "--server.address=127.0.0.1", "--server.port=28082",
                "--spring.datasource.url=jdbc:mysql://127.0.0.1:23307/blog_it?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8",
                "--spring.datasource.username=root", "--spring.datasource.password=blog-it-password",
                "--spring.data.redis.host=127.0.0.1", "--spring.data.redis.port=26379", "--spring.data.redis.database=15",
                "--blog.minio.endpoint=http://127.0.0.1:29000", "--blog.minio.public-url=http://127.0.0.1:29000",
                "--blog.minio.access-key=blog-it-user", "--blog.minio.secret-key=blog-it-minio-password",
                "--blog.minio.bucket=blog-it-" + run, "--blog.upload-dir=target/browser-e2e/uploads/" + run,
                "--jwt.secret=browser-e2e-only-signing-key-at-least-32-bytes", "--blog.admin.bootstrap-enabled=false",
                "--blog.subscription.worker-enabled=false", "--blog.auth.cookie.secure=false",
                "--blog.search.fulltext-enabled=true", "--blog.minio.enabled=true", "--spring.flyway.enabled=true",
                "--spring.sql.init.mode=never", "--spring.mail.host=", "--management.health.mail.enabled=false",
                "--spring.docker.compose.enabled=false"
        };
    }
}
