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
    @Autowired private CaptchaService captcha;
    @Autowired private Flyway flyway;
    @Autowired private AttachmentObjectStore objects;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Set<String> redisKeys=new HashSet<>();
    private String username,token;
    private long userId;

    @DynamicPropertySource
    static void isolate(DynamicPropertyRegistry registry) {
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
