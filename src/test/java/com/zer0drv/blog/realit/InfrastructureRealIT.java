package com.zer0drv.blog.realit;

import com.zer0drv.blog.auth.service.TokenService;
import com.zer0drv.blog.auth.validator.BlacklistJwtValidator;
import com.zer0drv.blog.common.captcha.CaptchaService;
import com.zer0drv.blog.attachment.service.AttachmentObjectStore;
import io.minio.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Network HTTP + actual MySQL/Flyway, Redis and MinIO. No fast-test base class or mocked beans. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="spring.config.location=classpath:/application-real-it.yaml")
@ContextConfiguration(initializers=RealInfrastructureSafety.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InfrastructureRealIT {
    private static final String RUN=UUID.randomUUID().toString().replace("-","");
    private static final String BUCKET="blog-it-"+RUN;
    private static final Path UPLOADS=Path.of("target","real-it-uploads",RUN).toAbsolutePath().normalize();
    private static final String PASSWORD="Real-it-passw0rd";
    private static final String HASH=new BCryptPasswordEncoder().encode(PASSWORD);
    private static final byte[] PNG=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jU1sAAAAASUVORK5CYII=");
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private StringRedisTemplate redis;
    @Autowired private MinioClient minio;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtDecoder decoder;
    @Autowired private TokenService tokens;
    @Autowired private com.zer0drv.blog.auth.service.RefreshSessionService refreshSessions;
    @Autowired private CaptchaService captcha;
    @Autowired private Flyway flyway;
    @Autowired private AttachmentObjectStore objects;
    @Autowired private com.zer0drv.blog.admin.service.OwnerOperations owners;
    @Autowired private com.zer0drv.blog.admin.service.AdminCommentService adminComments;
    @Autowired private com.zer0drv.blog.social.service.NotificationService notifications;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Set<String> redisKeys=new HashSet<>();
    private String username,token;
    private long userId;

    @Test void logoutRevokesRotatedAccessAndRefreshButNotOtherDevice() {
        var original = decoder.decode(token);
        String first = refreshSessions.create(original);
        var rotated = refreshSessions.rotate(first);
        var otherAccess = decoder.decode(tokens.generateToken(userId,List.of("ROLE_USER")));
        String other = refreshSessions.create(otherAccess);
        tokens.blackToken(original.getId(),original.getExpiresAt());
        assertThrows(Exception.class, () -> decoder.decode(rotated.access()));
        assertThrows(com.zer0drv.blog.common.exception.BusinessException.class, () -> refreshSessions.rotate(rotated.refresh()));
        assertNotNull(refreshSessions.rotate(other));
    }

    @DynamicPropertySource
    static void isolate(DynamicPropertyRegistry registry) {
        registry.add("blog.totp.encryption-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        registry.add("blog.minio.bucket",()->BUCKET);
        registry.add("blog.upload-dir",UPLOADS::toString);
    }

    @BeforeAll
    void requireRealInfrastructure() throws Exception {
        assertFalse(org.mockito.Mockito.mockingDetails(redis).isMock());
        assertFalse(org.mockito.Mockito.mockingDetails(minio).isMock());
        try(var connection=dataSource.getConnection()) {
            var metadata=connection.getMetaData();
            assertEquals("MySQL",metadata.getDatabaseProductName());
            assertTrue(metadata.getDatabaseMajorVersion()>=8);
            assertEquals(ZoneOffset.UTC,ZoneId.systemDefault().normalized());
            assertEquals("00:00:00",jdbc.queryForObject("SELECT TIMEDIFF(NOW(),UTC_TIMESTAMP())",String.class));
            assertEquals("PONG",redis.execute((RedisCallback<String>) c->c.ping()));
            assertTrue(minio.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build()));
            assertTrue(flyway.info().pending().length==0);
            Path evidence=Path.of("target","real-it-reports","infrastructure.json");
            Files.createDirectories(evidence.getParent());
            Files.writeString(evidence,mapper.writeValueAsString(Map.of("runId",RUN,
                    "mysql",metadata.getDatabaseProductVersion(),"driver",metadata.getDriverName(),
                    "redisPing","PONG","minioBucket",BUCKET,"migration",flyway.info().current().getVersion().toString())));
        }
    }

    @BeforeEach
    void seedIsolatedAccount() throws Exception {
        username="rit_"+UUID.randomUUID().toString().replace("-","").substring(0,20);
        jdbc.update("INSERT INTO `user`(username,password,email,nickname,role,status) VALUES(?,?,?,'Real IT','ADMIN',0)",username,HASH,username+"@real-it.invalid");
        userId=jdbc.queryForObject("SELECT id FROM `user` WHERE username=?",Long.class,username);
        captcha.clearAttempts("login","127.0.0.1");
        token=login(null,null);
    }

    @AfterEach
    void clearOwnedRedisKeys() {
        captcha.clearAttempts("login","127.0.0.1");
        captcha.clearAttempts("login","u:"+username);
        redisKeys.add("blog:user-tokens:"+userId);
        redisKeys.add("blog:upload:daily:"+userId+":"+LocalDate.now());
        if(!redisKeys.isEmpty()) redis.delete(redisKeys);
        redisKeys.clear();
        // Scope every deletion to the account created by this test; no TRUNCATE/FLUSHDB.
        for(String table:new String[]{"subscription_delivery","subscription_publication","article_version","article_tag","article_like","article_favorite","comment"})
            jdbc.update("DELETE FROM "+table+" WHERE article_id IN (SELECT id FROM article WHERE author_id=?)",userId);
        jdbc.update("DELETE FROM article WHERE author_id=?",userId);
        jdbc.update("DELETE FROM attachment_cleanup WHERE object_key IN (SELECT object_key FROM attachment WHERE user_id=?)",userId);
        jdbc.update("DELETE FROM attachment WHERE user_id=?",userId);
        jdbc.update("DELETE FROM attachment_group WHERE user_id=?",userId);
        jdbc.update("DELETE FROM notification WHERE user_id=? OR actor_id=?",userId,userId);
        jdbc.update("DELETE FROM sys_config_audit WHERE operator_id=?",userId);
        for (String table : List.of("totp_challenge", "totp_recovery", "user_totp", "auth_refresh_session"))
            jdbc.update("DELETE FROM " + table + " WHERE user_id=?", userId);
        jdbc.update("DELETE FROM `user` WHERE id=? AND username=?",userId,username);
    }

    @AfterAll
    void removeRunBucketAndClients() throws Exception {
        try {
            // The name is generated in this process and enforced before the application starts.
            assertEquals("blog-it-"+RUN,BUCKET);
            for(var item:minio.listObjects(ListObjectsArgs.builder().bucket(BUCKET).recursive(true).build()))
                minio.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(item.get().objectName()).build());
            minio.removeBucket(RemoveBucketArgs.builder().bucket(BUCKET).build());
        } finally { http.close(); }
        // Database rows are confined to the compose project's disposable tmpfs database.
    }

    @Test
    void loginCookieRedisIndexLogoutAndUserWideRevocation() throws Exception {
        var first=decoder.decode(token);
        assertTrue(redis.opsForSet().isMember("blog:user-tokens:"+userId,first.getId()));
        assertTrue(redis.getExpire("blog:user-tokens:"+userId)>0);
        var profile=send("GET","/auth/me",null,null,"AUTH_TOKEN="+token);
        assertEquals(200,profile.statusCode());assertEquals(username,body(profile).path("data").path("username").asString());
        String second=login(null,null);
        ok(send("POST","/auth/logout",Map.of(),token,null));
        String blacklist=BlacklistJwtValidator.BLACKLIST_PREFIX+first.getId();redisKeys.add(blacklist);
        assertTrue(redis.hasKey(blacklist));assertTrue(redis.getExpire(blacklist)>0);
        assertEquals(401,send("GET","/auth/me",null,token,null).statusCode());
        assertEquals(200,send("GET","/auth/me",null,second,null).statusCode());
        tokens.blackUserTokens(userId);
        assertEquals(401,send("GET","/auth/me",null,second,null).statusCode());
        assertFalse(redis.hasKey("blog:user-tokens:"+userId));
    }

    @Test
    void captchaCountsExpireAndCodesAreConsumedByActualLogin() throws Exception {
        for(int i=0;i<3;i++) assertNotEquals("200",body(send("POST","/auth/login",Map.of("username",username,"password","incorrect"),null,null)).path("code").asString());
        String count="captcha:count:login:u:"+username;redisKeys.add(count);
        assertEquals("3",redis.opsForValue().get(count));assertTrue(redis.getExpire(count)>0);
        assertTrue(ok(send("GET","/auth/captcha/required?scene=login",null,null,null)).path("required").asBoolean());
        assertEquals(com.zer0drv.blog.common.response.StatusCode.CAPTCHA_REQUIRED.getCode(),body(send("POST","/auth/login",Map.of("username",username,"password",PASSWORD),null,null)).path("code").asString());
        JsonNode challenge=ok(send("GET","/auth/captcha?scene=login",null,null,null));
        String id=challenge.path("captchaId").asString(),key="captcha:login:"+id;redisKeys.add(key);
        String code=redis.opsForValue().get(key);assertNotNull(code);assertTrue(redis.getExpire(key)>0);
        assertTrue(challenge.path("imageBase64").asString().startsWith("data:image/png;base64,"));
        login(id,code);assertFalse(redis.hasKey(key));assertFalse(redis.hasKey(count));
        assertThrows(com.zer0drv.blog.common.exception.BusinessException.class,()->captcha.verifyForced("login",id,code));
        captcha.recordAttempt("login",username);String ttlKey="captcha:count:login:"+username;redisKeys.add(ttlKey);
        redis.expire(ttlKey,Duration.ofMillis(100));
        await(()->!redis.hasKey(ttlKey)); // real server TTL expiration, not simulated clock advancement
    }

    @Test
    void minioUploadIsAnonymouslyReadableThenObservedAndPhysicallyDeleted() throws Exception {
        JsonNode upload=upload();String url=upload.path("url").asString();long id=upload.path("id").asLong();
        var download=http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,download.statusCode());assertArrayEquals(PNG,download.body());
        String key=jdbc.queryForObject("SELECT object_key FROM attachment WHERE id=?",String.class,id);
        assertEquals("MINIO",jdbc.queryForObject("SELECT storage FROM attachment WHERE id=?",String.class,id));
        ok(send("DELETE","/attachments/"+id,null,token,null));
        assertNotNull(objects.stat("MINIO",key));
        // No sleep for the seven-day policy: create the candidate using its real inventory fingerprint.
        var object=objects.stat("MINIO",key);
        jdbc.update("INSERT INTO attachment_cleanup(storage,object_key,size_bytes,fingerprint,marked_at,eligible_at,operator_id) VALUES('MINIO',?,?,?,?,?,?)",
                key,object.size(),object.fingerprint(),Timestamp.from(Instant.now().minus(Duration.ofDays(8))),Timestamp.from(Instant.now().minusSeconds(1)),userId);
        long candidate=jdbc.queryForObject("SELECT id FROM attachment_cleanup WHERE object_key=?",Long.class,key);
        assertEquals("DELETED",ok(send("DELETE","/admin/attachments/cleanup/"+candidate,null,token,null)).path("state").asString());
        assertNull(objects.stat("MINIO",key));
        assertEquals(404,http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(PNG.length,jdbc.queryForObject("SELECT reclaimed_bytes FROM attachment_cleanup WHERE id=?",Long.class,candidate));
    }

    @Test
    void realRedisDraftPreventsMatureMinioDeletion() throws Exception {
        JsonNode upload=upload();long id=upload.path("id").asLong();String url=upload.path("url").asString();
        long article=createArticle("draft reference", "draft", "DRAFT","PUBLIC");
        ok(send("PUT","/articles/"+article+"/autosave",Map.of("title","draft","content","![]("+url+")","cover",url),token,null));
        String draft="blog:autosave:"+article+":"+userId;redisKeys.add(draft);
        assertNotNull(redis.opsForValue().get(draft));assertTrue(redis.getExpire(draft)>0);
        ok(send("DELETE","/attachments/"+id,null,token,null));
        String key=jdbc.queryForObject("SELECT object_key FROM attachment WHERE id=?",String.class,id);var object=objects.stat("MINIO",key);
        jdbc.update("INSERT INTO attachment_cleanup(storage,object_key,size_bytes,fingerprint,marked_at,eligible_at,operator_id) VALUES('MINIO',?,?,?,?,?,?)",
                key,object.size(),object.fingerprint(),Timestamp.from(Instant.now().minus(Duration.ofDays(8))),Timestamp.from(Instant.now().minusSeconds(1)),userId);
        long candidate=jdbc.queryForObject("SELECT id FROM attachment_cleanup WHERE object_key=?",Long.class,key);
        assertEquals("RETAINED",ok(send("DELETE","/admin/attachments/cleanup/"+candidate,null,token,null)).path("state").asString());
        assertNotNull(objects.stat("MINIO",key));
    }

    @Test
    void restartOnlyStorageChangeDoesNotRerouteActiveUploads() throws Exception {
        ok(send("PUT","/admin/sys/config",Map.of("blog.upload.storage","LOCAL"),token,null));
        // Storage changes are restart-only: current app must continue using MinIO.
        JsonNode uploaded=upload();assertTrue(uploaded.path("url").asString().startsWith("http"));
        ok(send("DELETE","/admin/sys/config/blog.upload.storage",null,token,null));
    }

    @Test
    void mysqlFulltextEnforcesAllPublicVisibilityFilters() throws Exception {
        String needle="回归搜索蓝鲸"+RUN.substring(0,6);
        long visible=createArticle("public "+needle,needle,"PUBLISHED","PUBLIC");
        createArticle("draft "+needle,needle,"DRAFT","PUBLIC");
        createArticle("private "+needle,needle,"PUBLISHED","PRIVATE");
        long password=createArticle("protected "+needle,needle,"PUBLISHED","PASSWORD");
        long future=createArticle("scheduled "+needle,needle,"PUBLISHED","PUBLIC");
        jdbc.update("UPDATE article SET publish_time=DATE_ADD(NOW(),INTERVAL 1 DAY) WHERE id=?",future);
        long trash=createArticle("trash "+needle,needle,"PUBLISHED","PUBLIC");jdbc.update("UPDATE article SET deleted=1 WHERE id=?",trash);
        long offline=createArticle("offline "+needle,needle,"PUBLISHED","PUBLIC");jdbc.update("UPDATE article SET status='OFFLINE' WHERE id=?",offline);
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM article WHERE MATCH(title,content_text) AGAINST(? IN NATURAL LANGUAGE MODE)",Long.class,needle)>=7);
        JsonNode found=ok(send("GET","/articles/search?keyword="+URLEncoder.encode(needle,StandardCharsets.UTF_8),null,null,null));
        assertEquals(1,found.path("total").asLong());assertEquals(visible,found.path("records").get(0).path("id").asLong());
        assertTrue(ok(send("GET","/articles/"+password,null,null,null)).path("passwordRequired").asBoolean());
    }

    @Test
    void logoutClosesRealWebsocketAndRevokedCookieCannotReconnect() throws Exception {
        var closed=new CompletableFuture<Integer>();var pong=new CompletableFuture<String>();
        var listener=new WebSocket.Listener() {
            @Override public void onOpen(WebSocket socket) { socket.request(1); }
            @Override public CompletionStage<?> onText(WebSocket socket,CharSequence text,boolean last) { pong.complete(text.toString());socket.request(1);return null; }
            @Override public CompletionStage<?> onClose(WebSocket socket,int code,String reason) { closed.complete(code);return null; }
        };
        WebSocket socket=http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3)).header("Cookie","AUTH_TOKEN="+token)
                .buildAsync(URI.create("ws://127.0.0.1:"+port+"/ws"),listener).get(5,TimeUnit.SECONDS);
        try {
            socket.sendText("ping",true).get(5,TimeUnit.SECONDS);assertEquals("pong",pong.get(5,TimeUnit.SECONDS));
            ok(send("POST","/auth/logout",Map.of(),token,null));assertNotNull(closed.get(5,TimeUnit.SECONDS));
            assertThrows(ExecutionException.class,()->http.newWebSocketBuilder().header("Cookie","AUTH_TOKEN="+token)
                    .buildAsync(URI.create("ws://127.0.0.1:"+port+"/ws"),listener).get(5,TimeUnit.SECONDS));
        } finally { socket.abort(); }
    }

    @Test
    void realCookieMutationsKeepCsrfHeaderProtection() throws Exception {
        var response=http.send(HttpRequest.newBuilder(uri("/admin/sys/config")).header("Cookie","AUTH_TOKEN="+token)
                .header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString("{}" )).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(403,response.statusCode());
    }

    private String login(String captchaId,String captchaCode) throws Exception {
        Map<String,Object> payload=new HashMap<>(Map.of("username",username,"password",PASSWORD));
        if(captchaId!=null) {payload.put("captchaId",captchaId);payload.put("captchaCode",captchaCode);}
        var response=send("POST","/auth/login",payload,null,null);String result=ok(response).path("access_token").asString();
        assertTrue(response.headers().allValues("set-cookie").stream().anyMatch(s->s.contains("AUTH_TOKEN=")&&s.contains("HttpOnly")));
        redisKeys.add(BlacklistJwtValidator.BLACKLIST_PREFIX+decoder.decode(result).getId());return result;
    }

    @Test
    void totpRevokesOldSessionsAndConcurrentRecoveryHasOneWinnerOnMysql() throws Exception {
        var setup = ok(send("POST", "/auth/totp/setup", Map.of("password", PASSWORD), token, null));
        String secret = setup.path("secret").asString();
        String otp = com.zer0drv.blog.auth.service.TotpCrypto.code(secret, Instant.now().getEpochSecond() / 30, 6);
        var recovery = ok(send("POST", "/auth/totp/confirm", Map.of("password", PASSWORD, "code", otp), token, null));
        assertEquals(10, recovery.size());
        assertEquals(401, send("GET", "/auth/me", null, token, null).statusCode());
        var primary = ok(send("POST", "/auth/login", Map.of("username", username, "password", PASSWORD), null, null));
        assertFalse(primary.has("access_token"));
        String challenge = primary.path("challenge").asString();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var calls = new ArrayList<Future<HttpResponse<String>>>();
            for (int i = 0; i < 2; i++) calls.add(executor.submit(() -> send("POST", "/auth/totp/login",
                    Map.of("challenge", challenge, "code", recovery.get(0).asString()), null, null)));
            int successes = 0;
            for (var call : calls) {
                var response = call.get(20, TimeUnit.SECONDS);
                if (body(response).path("code").asString().equals("200")) {
                    successes++;
                    String authenticated = body(response).path("data").path("access_token").asString();
                    redisKeys.add(BlacklistJwtValidator.BLACKLIST_PREFIX + decoder.decode(authenticated).getId());
                    assertEquals(username, ok(send("GET", "/auth/me", null, authenticated, null)).path("username").asString());
                } else assertEquals("40081", body(response).path("code").asString());
            }
            assertEquals(1, successes);
        }
        assertEquals(9, jdbc.queryForObject("SELECT COUNT(*) FROM totp_recovery WHERE user_id=?", Integer.class, userId));
    }
    @Test
    void concurrentRefreshUsesMysqlCurrentReadsAndReplayRevokesWinner() throws Exception {
        String refresh = refreshSessions.create(decoder.decode(token));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<HttpResponse<String>> action = () -> {
                start.await();
                return send("POST", "/auth/refresh", Map.of(), null, "REFRESH_TOKEN=" + refresh);
            };
            var first = pool.submit(action); var second = pool.submit(action); start.countDown();
            var responses = List.of(first.get(20,TimeUnit.SECONDS), second.get(20,TimeUnit.SECONDS));
            assertEquals(1,responses.stream().filter(r -> r.statusCode()==200).count());
            assertEquals(1,responses.stream().filter(r -> r.statusCode()==401).count());
            var winner = responses.stream().filter(r -> r.statusCode()==200).findFirst().orElseThrow();
            String cookie = winner.headers().allValues("set-cookie").stream().filter(v -> v.startsWith("AUTH_TOKEN=")).findFirst().orElseThrow().split(";",2)[0];
            assertEquals(401,send("GET","/auth/me",null,null,cookie).statusCode());
        }
    }

    @Test
    void concurrentOwnershipTransfersHaveOneWinnerAndPreserveAnActiveOwner() throws Exception {
        jdbc.update("UPDATE `user` SET role='SUPER_ADMIN' WHERE id=?", userId);
        var targets = new LinkedHashMap<Long,String>();
        try {
            for (int i=0;i<2;i++) {
                String name="transfer_"+UUID.randomUUID().toString().replace("-","").substring(0,16);
                jdbc.update("INSERT INTO `user`(username,password,email,nickname,role,status) VALUES(?,?,?,'Transfer IT','ADMIN',0)",name,HASH,name+"@real-it.invalid");
                targets.put(jdbc.queryForObject("SELECT id FROM `user` WHERE username=?",Long.class,name),name);
            }
            try (var pool=Executors.newFixedThreadPool(2)) {
                var start=new CountDownLatch(1);
                var calls=new ArrayList<Future<Boolean>>();
                for (var target:targets.entrySet()) calls.add(pool.submit(() -> {
                    start.await();
                    try {
                        owners.execute(userId,target.getKey(),new com.zer0drv.blog.admin.service.OwnerOperations.Request("TRANSFER",PASSWORD,null,target.getValue(),null));
                        return true;
                    } catch (com.zer0drv.blog.common.exception.BusinessException expected) { return false; }
                }));
                start.countDown();
                int successes=0;
                for (var call:calls) if(call.get(20,TimeUnit.SECONDS)) successes++;
                assertEquals(1,successes);
            }
            assertEquals("ADMIN",jdbc.queryForObject("SELECT role FROM `user` WHERE id=?",String.class,userId));
            long winner=targets.keySet().stream().filter(id -> "SUPER_ADMIN".equals(jdbc.queryForObject("SELECT role FROM `user` WHERE id=? AND status=0",String.class,id))).findFirst().orElseThrow();
            assertEquals(1,targets.keySet().stream().filter(id -> "SUPER_ADMIN".equals(jdbc.queryForObject("SELECT role FROM `user` WHERE id=?",String.class,id))).count());
            assertThrows(Exception.class,()->decoder.decode(token));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM owner_operation_audit WHERE operator_id=? AND outcome='SUCCESS'",Integer.class,userId));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM owner_operation_audit WHERE operator_id=? AND outcome='DENIED'",Integer.class,userId));
            assertThrows(com.zer0drv.blog.common.exception.BusinessException.class,()->owners.execute(winner,winner,
                    new com.zer0drv.blog.admin.service.OwnerOperations.Request("FREEZE",PASSWORD,null,targets.get(winner),null)));
            assertEquals(0,jdbc.queryForObject("SELECT status FROM `user` WHERE id=?",Integer.class,winner));
        } finally {
            jdbc.update("DELETE FROM owner_operation_audit WHERE operator_id=?",userId);
            for (long id:targets.keySet()) {
                jdbc.update("DELETE FROM owner_operation_audit WHERE operator_id=? OR target_id=?",id,id);
                jdbc.update("DELETE FROM auth_refresh_session WHERE user_id=?",id);
                jdbc.update("DELETE FROM `user` WHERE id=?",id);
                redisKeys.add("blog:user-tokens:"+id);
            }
        }
    }

    @Test
    void concurrentCommentDeletionAndDeliveryNeverExposeSnapshotsAndRetractionIsIdempotent() throws Exception {
        long article=createArticle("notification race","public content","PUBLISHED","PUBLIC");
        String secret="private snapshot "+UUID.randomUUID();
        jdbc.update("INSERT INTO comment(article_id,user_id,content) VALUES(?,?,?)",article,userId,secret);
        long root=jdbc.queryForObject("SELECT id FROM comment WHERE article_id=?",Long.class,article);
        jdbc.update("INSERT INTO comment(article_id,user_id,parent_id,content) VALUES(?,?,?,'reply')",article,userId,root);
        long reply=jdbc.queryForObject("SELECT id FROM comment WHERE parent_id=?",Long.class,root);
        try {
            // A removed parent must not hide a still valid reply.
            jdbc.update("INSERT INTO comment_notification_removal(comment_id,reason) VALUES(?,'DELETED')",root);
            jdbc.update("UPDATE comment SET deleted=1 WHERE id=?",root);
            notifications.notify(com.zer0drv.blog.social.service.NotificationIntent.commentReply(userId,null,article,reply,secret));
            assertEquals("AVAILABLE",notifications.forComment(reply).getFirst().getContentState());
            try (var pool=Executors.newFixedThreadPool(2)) {
                var start=new CountDownLatch(1);
                var deletion=pool.submit(() -> {start.await(); adminComments.forceDelete(root); return null;});
                var delivery=pool.submit(() -> {start.await(); notifications.notify(com.zer0drv.blog.social.service.NotificationIntent.commentReply(userId,null,article,reply,secret)); return null;});
                start.countDown(); deletion.get(20,TimeUnit.SECONDS); delivery.get(20,TimeUnit.SECONDS);
            }
            var visible=notifications.forComment(reply);
            assertEquals(2,visible.size());
            for (var notification:visible) {
                assertEquals("ADMIN_REMOVED",notification.getContentState());
                assertNull(notification.getArticleId()); assertNull(notification.getCommentId());
                assertFalse(notification.getSummary().contains(secret));
                assertEquals("",jdbc.queryForObject("SELECT summary FROM notification WHERE id=?",String.class,notification.getId()));
            }
            assertEquals(2,notifications.unreadCount(decoder.decode(token)));
            try (var pool=Executors.newFixedThreadPool(2)) {
                var start=new CountDownLatch(1);
                Callable<Void> retract=()->{start.await(); for(var notification:visible) notifications.retract(notification.getId(),userId); return null;};
                var first=pool.submit(retract); var second=pool.submit(retract); start.countDown();
                first.get(20,TimeUnit.SECONDS); second.get(20,TimeUnit.SECONDS);
            }
            assertTrue(notifications.forComment(reply).isEmpty());
            assertEquals(0,notifications.unreadCount(decoder.decode(token)));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM notification_retraction_audit WHERE operator_id=?",Integer.class,userId));
        } finally {
            jdbc.update("DELETE FROM comment_notification_removal WHERE comment_id IN (?,?)",root,reply);
            jdbc.update("DELETE FROM notification_retraction_audit WHERE operator_id=?",userId);
        }
    }

    private JsonNode upload() throws Exception {
        String boundary="real-it-"+UUID.randomUUID();
        byte[] prefix=("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"pixel.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        var payload=new java.io.ByteArrayOutputStream();payload.write(prefix);payload.write(PNG);payload.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(uri("/upload/image")).timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+token)
                .header("X-Requested-With","XMLHttpRequest").header("Content-Type","multipart/form-data; boundary="+boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload.toByteArray())).build();
        return ok(http.send(request,HttpResponse.BodyHandlers.ofString()));
    }
    private long createArticle(String title,String content,String status,String visibility) throws Exception {
        var payload=new HashMap<String,Object>(Map.of("title",title,"content",content,"status",status,"visibility",visibility,"editorType","MARKDOWN"));
        if(visibility.equals("PASSWORD"))payload.put("accessPassword","read-it");
        return ok(send("POST","/articles",payload,token,null)).asLong();
    }
    private HttpResponse<String> send(String method,String path,Object payload,String bearer,String cookie) throws Exception {
        var request=HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15)).header("X-Requested-With","XMLHttpRequest");
        if(bearer!=null)request.header("Authorization","Bearer "+bearer);if(cookie!=null)request.header("Cookie",cookie);
        var body=payload==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload));
        if(payload!=null)request.header("Content-Type","application/json");
        return http.send(request.method(method,body).build(),HttpResponse.BodyHandlers.ofString());
    }
    private URI uri(String path) {return URI.create("http://127.0.0.1:"+port+path);}
    private JsonNode body(HttpResponse<String> response) {return mapper.readTree(response.body());}
    private JsonNode ok(HttpResponse<String> response) {assertEquals(200,response.statusCode());var json=body(response);assertEquals("200",json.path("code").asString(),()->json.path("message").asString());return json.path("data");}
    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(25);
        assertTrue(condition.getAsBoolean(),"Redis TTL did not expire within five seconds");
    }
}
