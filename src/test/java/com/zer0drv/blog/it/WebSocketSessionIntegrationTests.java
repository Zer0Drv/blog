package com.zer0drv.blog.it;

import com.zer0drv.blog.auth.service.TokenService;
import com.zer0drv.blog.auth.validator.BlacklistJwtValidator;
import com.zer0drv.blog.social.service.RealtimePushService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真正启动嵌入式服务，用 JDK WebSocket 客户端验证握手、业务帧与关闭帧。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketSessionIntegrationTests extends IntegrationTestSupport {
    @LocalServerPort
    private int port;
    @Autowired
    private JwtEncoder encoder;
    @Autowired
    private TokenService tokens;
    @Autowired
    private RealtimePushService push;
    @Autowired
    private StringRedisTemplate redis;

    private final Set<String> blacklist = ConcurrentHashMap.newKeySet();
    private final List<WebSocket> sockets = new ArrayList<>();
    private HttpClient client;

    @BeforeEach
    void prepareRedisAndClient() {
        client = HttpClient.newHttpClient();
        when(redis.hasKey(anyString())).thenAnswer(call -> blacklist.contains(call.getArgument(0)));
        var values = redis.opsForValue();
        doAnswer(call -> { blacklist.add(call.getArgument(0)); return null; })
                .when(values).set(startsWith(BlacklistJwtValidator.BLACKLIST_PREFIX), eq("1"), any(Duration.class));
    }

    @AfterEach
    void closeClients() {
        sockets.forEach(WebSocket::abort);
        client.close();
    }

    private String token(long userId, String jti, Instant expiresAt) {
        var claims = JwtClaimsSet.builder().subject(String.valueOf(userId))
                .issuedAt(expiresAt == null ? Instant.now().minusSeconds(1) : expiresAt.minusSeconds(3600))
                .claim("roles", List.of("ROLE_USER"));
        if (jti != null) claims.id(jti);
        if (expiresAt != null) claims.expiresAt(expiresAt);
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    }

    private Listener connect(String token) throws Exception {
        Listener listener = new Listener();
        WebSocket socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                .header("Cookie", "AUTH_TOKEN=" + token)
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), listener).get(5, TimeUnit.SECONDS);
        sockets.add(socket);
        socket.sendText("ping", true).get(5, TimeUnit.SECONDS);
        assertEquals("pong", listener.frames.poll(5, TimeUnit.SECONDS));
        return listener;
    }

    private void assertRejected(String token) {
        assertThrows(ExecutionException.class, () -> client.newWebSocketBuilder()
                .header("Cookie", "AUTH_TOKEN=" + token)
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new Listener()).get(5, TimeUnit.SECONDS));
    }

    @Test
    void logoutClosesOnlyMatchingTokenAndFreshTokenReconnects() throws Exception {
        long userId = seedUser(unique("it_ws_logout_"), "WS 用户", "USER");
        Instant expiry = Instant.now().plusSeconds(60);
        String firstToken = token(userId, unique("ws_jti_"), expiry);
        String secondToken = token(userId, unique("ws_jti_"), expiry);
        Listener first = connect(firstToken);
        Listener second = connect(secondToken);
        push.pushToUser(userId, "private_message", "before");
        assertTrue(first.frames.poll(5, TimeUnit.SECONDS).contains("private_message"));
        assertTrue(second.frames.poll(5, TimeUnit.SECONDS).contains("private_message"));

        mockMvc.perform(post("/auth/logout").header(HttpHeaders.AUTHORIZATION, bearer(firstToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("200"));
        assertEquals(1008, first.closed.get(5, TimeUnit.SECONDS));
        assertRejected(firstToken);
        mockMvc.perform(get("/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(firstToken)))
                .andExpect(status().isUnauthorized());

        push.pushToUser(userId, "notification", "after");
        assertNull(first.frames.poll());
        assertTrue(second.frames.poll(5, TimeUnit.SECONDS).contains("notification"));
        assertFalse(second.closed.isDone());
        Listener fresh = connect(token(userId, unique("ws_fresh_"), expiry));
        push.pushToUser(userId, "notification", "reconnected");
        assertTrue(fresh.frames.poll(5, TimeUnit.SECONDS).contains("reconnected"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ban", "role", "password"})
    void accountChangesCloseAllSessionsAndRejectOldHttpAndHandshakes(String action) throws Exception {
        long userId = seedUser(unique("it_ws_account_"), "WS 用户", "USER");
        Instant expiry = Instant.now().plusSeconds(60);
        String jtiA = unique("ws_a_");
        String jtiB = unique("ws_b_");
        String tokenA = token(userId, jtiA, expiry);
        String tokenB = token(userId, jtiB, expiry);
        when(redis.opsForSet().members("blog:user-tokens:" + userId)).thenReturn(Set.of(jtiA, jtiB));
        Listener first = connect(tokenA);
        Listener second = connect(tokenB);

        if (action.equals("password")) {
            mockMvc.perform(put("/auth/password").header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"oldPassword\":\"" + TEST_PASSWORD + "\",\"newPassword\":\"Ws-new-pass123!\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("200"));
        } else {
            String admin = unique("it_ws_admin_");
            seedUser(admin, "管理员", "ADMIN");
            mockMvc.perform(put("/admin/users/{id}/" + action, userId)
                            .header(HttpHeaders.AUTHORIZATION, bearerOf(admin))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"AUTHOR\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("200"));
        }

        assertEquals(1008, first.closed.get(5, TimeUnit.SECONDS));
        assertEquals(1008, second.closed.get(5, TimeUnit.SECONDS));
        push.pushToUser(userId, "notification", "blocked");
        assertNull(first.frames.poll());
        assertNull(second.frames.poll());
        assertRejected(tokenA);
        assertRejected(tokenB);
        mockMvc.perform(get("/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void idleConnectionClosesAtExpiryAndExpiredHandshakeIsRejectedWithoutClockSkew() throws Exception {
        long userId = seedUser(unique("it_ws_expiry_"), "WS 用户", "USER");
        String expiredSoon = token(userId, unique("ws_short_"), Instant.now().plusSeconds(10));
        Listener listener = connect(expiredSoon);
        assertEquals(1008, listener.closed.get(15, TimeUnit.SECONDS));
        push.pushToUser(userId, "notification", "expired");
        assertNull(listener.frames.poll());
        // 即使 JWT 默认时间校验允许时钟偏差，WS 不再接受已到 exp 的连接。
        assertRejected(expiredSoon);
        mockMvc.perform(get("/auth/me").header(HttpHeaders.AUTHORIZATION,
                        bearer(token(userId, unique("ws_expired_"), Instant.now().minusSeconds(120)))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void idleSessionSeesRevocationFromSharedBlacklistWithoutLocalEvent() throws Exception {
        long userId = seedUser(unique("it_ws_remote_"), "WS 用户", "USER");
        String jti = unique("ws_remote_");
        Listener listener = connect(token(userId, jti, Instant.now().plusSeconds(60)));
        blacklist.add(BlacklistJwtValidator.BLACKLIST_PREFIX + jti);
        assertEquals(1008, listener.closed.get(5, TimeUnit.SECONDS));
        assertNull(listener.frames.poll());
    }

    @Test
    void missingCookieAndMissingTokenIdentityAreRejected() {
        long userId = seedUser(unique("it_ws_missing_"), "WS 用户", "USER");
        assertRejected(token(userId, null, Instant.now().plusSeconds(60)));
        assertRejected(token(userId, unique("ws_no_exp_"), null));
        assertThrows(ExecutionException.class, () -> client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws?token=ignored"), new Listener())
                .get(5, TimeUnit.SECONDS));
    }

    private static class Listener implements WebSocket.Listener {
        private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket socket) { socket.request(1); }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) { frames.add(partial.toString()); partial.setLength(0); }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
            closed.complete(code);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket socket, Throwable error) { closed.completeExceptionally(error); }
    }
}
