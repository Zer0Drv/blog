package com.zer0drv.blog.social;

import com.zer0drv.blog.auth.event.TokenRevoked;
import com.zer0drv.blog.auth.event.UserTokensRevoked;
import com.zer0drv.blog.auth.service.impl.TokenServiceImpl;
import com.zer0drv.blog.auth.validator.BlacklistJwtValidator;
import com.zer0drv.blog.config.WebSocketConfig;
import com.zer0drv.blog.social.service.impl.RealtimePushServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebSocketSessionInvalidationTest {
    private StringRedisTemplate redis;
    private TokenServiceImpl tokens;
    private ValueOperations<String, String> values;
    private RealtimePushServiceImpl push;
    private final Set<String> blacklist = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        values = redis.opsForValue();
        when(redis.hasKey(anyString())).thenAnswer(call -> blacklist.contains(call.getArgument(0)));
        doAnswer(call -> { blacklist.add(call.getArgument(0)); return null; })
                .when(values).set(anyString(), eq("1"), any(Duration.class));
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof TokenRevoked token) push.onTokenRevoked(token);
            if (event instanceof UserTokensRevoked user) push.onUserTokensRevoked(user);
        };
        tokens = new TokenServiceImpl(mock(JwtEncoder.class), redis, publisher);
        ReflectionTestUtils.setField(tokens, "expirationMs", 3600000L);
        push = new RealtimePushServiceImpl(JsonMapper.builder().build(), tokens);
    }

    private WebSocketSession session(String id, String jti, Instant expiry) {
        WebSocketSession session = mock(WebSocketSession.class);
        lenient().when(session.getId()).thenReturn(id);
        lenient().when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(Map.of(
                WebSocketConfig.ATTR_TOKEN_ID, jti, WebSocketConfig.ATTR_EXPIRES_AT, expiry));
        return session;
    }

    @Test
    void singleTokenRevocationClosesAllItsConnectionsButKeepsOtherTokensAndUsers() throws Exception {
        Instant expiry = Instant.now().plusSeconds(60);
        var first = session("one", "token-a", expiry);
        var second = session("two", "token-a", expiry);
        var otherToken = session("three", "token-b", expiry);
        var otherUser = session("four", "token-c", expiry);
        push.register(1L, first);
        push.register(1L, second);
        push.register(1L, otherToken);
        push.register(2L, otherUser);

        tokens.blackToken("token-a", expiry);
        push.pushToUser(1L, "notification", "new");
        push.pong(1L, first);
        push.pushToUser(2L, "notification", "new");

        verify(first).close(CloseStatus.POLICY_VIOLATION);
        verify(second).close(CloseStatus.POLICY_VIOLATION);
        verify(first, never()).sendMessage(any());
        verify(second, never()).sendMessage(any());
        verify(otherToken).sendMessage(any());
        verify(otherUser).sendMessage(any());
        verify(otherToken, never()).close(any());
    }

    @Test
    void userRevocationClosesSessionsEvenWhenRedisActiveSetIsEmptyAndNewTokenCanReconnect() throws Exception {
        Instant expiry = Instant.now().plusSeconds(60);
        var first = session("one", "a", expiry);
        var second = session("two", "b", expiry);
        push.register(1L, first);
        push.register(1L, second);
        when(redis.opsForSet().members("blog:user-tokens:1")).thenReturn(Set.of());

        tokens.blackUserTokens(1L);
        push.pushToUser(1L, "notification", "no");
        var replacement = session("new", "new-token", expiry);
        push.register(1L, replacement);
        push.pushToUser(1L, "notification", "yes");

        verify(first).close(CloseStatus.POLICY_VIOLATION);
        verify(second).close(CloseStatus.POLICY_VIOLATION);
        verify(first, never()).sendMessage(any());
        verify(second, never()).sendMessage(any());
        verify(replacement).sendMessage(any());
    }

    @Test
    void expiredMissingAndAlreadyRevokedCredentialsAreRejectedAtRegistration() throws Exception {
        var expired = session("expired", "a", Instant.now().minusSeconds(1));
        var revoked = session("revoked", "b", Instant.now().plusSeconds(60));
        blacklist.add(BlacklistJwtValidator.BLACKLIST_PREFIX + "b");
        var missing = mock(WebSocketSession.class);
        when(missing.isOpen()).thenReturn(true);
        when(missing.getAttributes()).thenReturn(Map.of());
        push.register(1L, expired);
        push.register(1L, revoked);
        push.register(1L, missing);
        push.pushToUser(1L, "notification", "no");
        for (var session : Set.of(expired, revoked, missing)) {
            verify(session).close(CloseStatus.POLICY_VIOLATION);
            verify(session, never()).sendMessage(any());
        }
    }

    @Test
    void sharedBlacklistIsRecheckedBeforeEachFrameAndDuringIdleSweep() throws Exception {
        var first = session("one", "a", Instant.now().plusSeconds(60));
        var second = session("two", "b", Instant.now().plusSeconds(60));
        push.register(1L, first);
        push.register(1L, second);
        // 模拟另一个实例写黑名单，没有本机事件。
        blacklist.add(BlacklistJwtValidator.BLACKLIST_PREFIX + "a");
        push.pushToUser(1L, "notification", "payload");
        blacklist.add(BlacklistJwtValidator.BLACKLIST_PREFIX + "b");
        push.sweepSessions();
        push.sweepSessions();

        verify(first, never()).sendMessage(any());
        verify(first).close(CloseStatus.POLICY_VIOLATION);
        verify(second).sendMessage(any());
        verify(second).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void failedBlacklistLookupFailsClosedAndFailedCloseStillEvicts() throws Exception {
        var session = session("one", "a", Instant.now().plusSeconds(60));
        push.register(1L, session);
        when(redis.hasKey(anyString())).thenThrow(new IllegalStateException("Redis down"));
        doThrow(new IOException("close failed")).when(session).close(any());
        assertDoesNotThrow(() -> push.pushToUser(1L, "notification", "no"));
        push.pushToUser(1L, "notification", "still no");
        push.pong(1L, session);
        verify(session, never()).sendMessage(any());
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void revocationWaitsForPreviouslyAuthorizedSendAndNoSendCanStartAfterItReturns() throws Exception {
        var session = session("one", "a", Instant.now().plusSeconds(60));
        push.register(1L, session);
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        CountDownLatch revocationWritten = new CountDownLatch(1);
        doAnswer(call -> {
            sending.countDown();
            assertTrue(releaseSend.await(5, TimeUnit.SECONDS));
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        doAnswer(call -> {
            blacklist.add(call.getArgument(0));
            revocationWritten.countDown();
            return null;
        }).when(values).set(anyString(), eq("1"), any(Duration.class));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var send = executor.submit(() -> push.pushToUser(1L, "notification", "before"));
            assertTrue(sending.await(5, TimeUnit.SECONDS));
            var revoke = executor.submit(() -> tokens.blackToken("a", Instant.now().plusSeconds(60)));
            try {
                assertTrue(revocationWritten.await(5, TimeUnit.SECONDS));
                assertFalse(revoke.isDone());
            } finally { releaseSend.countDown(); }
            send.get(5, TimeUnit.SECONDS);
            revoke.get(5, TimeUnit.SECONDS);
        }
        push.pushToUser(1L, "notification", "after");
        push.pong(1L, session);
        verify(session, times(1)).sendMessage(any());
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void finalFractionOfSecondIsStillBlacklistedAndUserRevocationWritesAllTokens() {
        tokens.blackToken("short", Instant.now().plusMillis(900));
        assertTrue(blacklist.contains(BlacklistJwtValidator.BLACKLIST_PREFIX + "short"));
        when(redis.opsForSet().members("blog:user-tokens:1")).thenReturn(Set.of("a", "b"));
        tokens.blackUserTokens(1L);
        assertFalse(tokens.isTokenActive("a", Instant.now().plusSeconds(60)));
        assertFalse(tokens.isTokenActive("b", Instant.now().plusSeconds(60)));
        verify(redis).delete("blog:user-tokens:1");
    }

    @Test
    void revocationDuringRegistrationCannotMissTheNewConnection() throws Exception {
        var session = session("racing", "a", Instant.now().plusSeconds(60));
        CountDownLatch checking = new CountDownLatch(1);
        CountDownLatch releaseCheck = new CountDownLatch(1);
        CountDownLatch revoked = new CountDownLatch(1);
        when(redis.hasKey(anyString())).thenAnswer(call -> {
            boolean captured = blacklist.contains(call.getArgument(0));
            checking.countDown();
            assertTrue(releaseCheck.await(5, TimeUnit.SECONDS));
            return captured;
        });
        doAnswer(call -> {
            blacklist.add(call.getArgument(0));
            revoked.countDown();
            return null;
        }).when(values).set(anyString(), eq("1"), any(Duration.class));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var registration = executor.submit(() -> push.register(1L, session));
            assertTrue(checking.await(5, TimeUnit.SECONDS));
            var revocation = executor.submit(() -> tokens.blackToken("a", Instant.now().plusSeconds(60)));
            try { assertTrue(revoked.await(5, TimeUnit.SECONDS)); }
            finally { releaseCheck.countDown(); }
            registration.get(5, TimeUnit.SECONDS);
            revocation.get(5, TimeUnit.SECONDS);
        }
        push.pushToUser(1L, "notification", "no");
        verify(session, never()).sendMessage(any());
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void shutdownClosesAndEvictsAllUsersEvenWhenOneCloseFails() throws Exception {
        var first = session("one", "a", Instant.now().plusSeconds(60));
        var second = session("two", "b", Instant.now().plusSeconds(60));
        push.register(1L, first);
        push.register(2L, second);
        doThrow(new IOException("close failed")).when(first).close(any());
        assertDoesNotThrow(push::shutdown);
        push.pushToUser(1L, "notification", "no");
        push.pushToUser(2L, "notification", "no");
        verify(first).close(CloseStatus.GOING_AWAY);
        verify(second).close(CloseStatus.GOING_AWAY);
        verify(first, never()).sendMessage(any());
        verify(second, never()).sendMessage(any());
    }
}
