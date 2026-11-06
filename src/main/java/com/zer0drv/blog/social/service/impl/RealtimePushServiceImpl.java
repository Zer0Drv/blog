package com.zer0drv.blog.social.service.impl;

import com.zer0drv.blog.auth.event.TokenRevoked;
import com.zer0drv.blog.auth.event.UserTokensRevoked;
import com.zer0drv.blog.auth.service.TokenService;
import com.zer0drv.blog.config.WebSocketConfig;
import com.zer0drv.blog.social.service.RealtimePushService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本机连接表；每帧检查共享黑名单，空闲连接每秒复检。
 * 发送、检查和吊销关闭共用服务持有的连接锁：本机吊销返回时，已进入的发送已结束。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RealtimePushServiceImpl implements RealtimePushService {

    private final Map<Long, ConcurrentHashMap<WebSocketSession, Connection>> sessions = new ConcurrentHashMap<>();

    private static final class Connection {
        private final WebSocketSession session;
        private final Object lock = new Object();

        private Connection(WebSocketSession session) {
            this.session = session;
        }
    }
    private final ObjectMapper objectMapper;
    private final TokenService tokenService;

    @Override
    public void register(Long userId, WebSocketSession session) {
        if (session == null) return;
        if (userId == null) {
            close(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        // 先入表再复检，收口握手校验与注册之间的吊销竞态。
        var bucket = sessions.compute(userId, (_, existing) -> {
            var connections = existing == null ? new ConcurrentHashMap<WebSocketSession, Connection>() : existing;
            connections.computeIfAbsent(session, Connection::new);
            return connections;
        });
        Connection connection = bucket.get(session);
        if (connection != null) {
            synchronized (connection.lock) {
                validateOrEvict(userId, connection);
            }
        }
    }

    @Override
    public void unregister(Long userId, WebSocketSession session) {
        if (userId == null || session == null) return;
        sessions.computeIfPresent(userId, (_, bucket) -> {
            bucket.remove(session);
            return bucket.isEmpty() ? null : bucket;
        });
    }

    @Override
    public void pushToUser(Long userId, String type, Object payload) {
        if (userId == null) return;
        try {
            var bucket = sessions.get(userId);
            if (bucket == null || bucket.isEmpty()) return;
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("type", type);
            frame.put("data", payload);
            TextMessage message = new TextMessage(objectMapper.writeValueAsString(frame));
            for (Connection connection : bucket.values()) sendOrEvict(userId, connection, message);
        } catch (Exception e) {
            // 不记录异常消息，避免第三方异常带入原始凭据或消息正文。
            log.warn("实时推送失败：userId={}, type={}", userId, type);
        }
    }

    @Override
    public void pong(Long userId, WebSocketSession session) {
        if (userId == null || session == null) return;
        var bucket = sessions.get(userId);
        Connection connection = bucket == null ? null : bucket.get(session);
        if (connection != null) sendOrEvict(userId, connection, new TextMessage("pong"));
    }

    private void sendOrEvict(Long userId, Connection connection, TextMessage message) {
        synchronized (connection.lock) {
            if (!validateOrEvict(userId, connection)) return;
            try {
                connection.session.sendMessage(message);
            } catch (Exception e) {
                evict(userId, connection, CloseStatus.SERVER_ERROR);
            }
        }
    }

    // Callers hold the connection lock. A stale iterator cannot send on a removed connection.
    private boolean validateOrEvict(Long userId, Connection connection) {
        WebSocketSession session = connection.session;
        var bucket = sessions.get(userId);
        if (bucket == null || bucket.get(session) != connection) return false;
        if (!session.isOpen()) {
            unregister(userId, session);
            return false;
        }
        Object jti = session.getAttributes().get(WebSocketConfig.ATTR_TOKEN_ID);
        Object expiry = session.getAttributes().get(WebSocketConfig.ATTR_EXPIRES_AT);
        try {
            if (jti instanceof String tokenId && expiry instanceof Instant expiresAt
                    && tokenService.isTokenActive(tokenId, expiresAt)) return true;
        } catch (Exception e) {
            // Redis 不可用时关闭连接，禁止继续发送未经确认授权的帧。
        }
        evict(userId, connection, CloseStatus.POLICY_VIOLATION);
        return false;
    }

    @EventListener
    public void onTokenRevoked(TokenRevoked event) {
        sessions.forEach((userId, bucket) -> {
            for (Connection connection : bucket.values()) {
                if (event.jti() != null && event.jti().equals(connection.session.getAttributes().get(WebSocketConfig.ATTR_TOKEN_ID))) {
                    synchronized (connection.lock) { evict(userId, connection, CloseStatus.POLICY_VIOLATION); }
                }
            }
        });
    }

    @EventListener
    public void onUserTokensRevoked(UserTokensRevoked event) {
        var bucket = sessions.get(event.userId());
        if (bucket == null) return;
        for (Connection connection : bucket.values()) {
            synchronized (connection.lock) { evict(event.userId(), connection, CloseStatus.POLICY_VIOLATION); }
        }
    }

    @EventListener
    public void onRemoteRevocation(com.zer0drv.blog.auth.event.RemoteSessionRevocation event) {
        if (event.userId() != null) onUserTokensRevoked(new UserTokensRevoked(event.userId()));
        else onTokenRevoked(new TokenRevoked(event.jti()));
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void sweepSessions() {
        sessions.forEach((userId, bucket) -> {
            for (Connection connection : bucket.values()) {
                synchronized (connection.lock) { validateOrEvict(userId, connection); }
            }
        });
    }

    private void evict(Long userId, Connection connection, CloseStatus status) {
        // Close while still registered and locked, so a duplicate registration cannot acquire a new lock mid-close.
        var bucket = sessions.get(userId);
        if (bucket == null || bucket.get(connection.session) != connection) return;
        close(connection.session, status);
        sessions.computeIfPresent(userId, (_, connections) -> {
            connections.remove(connection.session, connection);
            return connections.isEmpty() ? null : connections;
        });
    }

    private void close(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) session.close(status);
        } catch (Exception e) {
            log.debug("WebSocket 关闭失败：sessionId={}", session.getId());
        }
    }

    @PreDestroy
    public void shutdown() {
        sessions.forEach((userId, bucket) -> {
            for (Connection connection : bucket.values()) {
                synchronized (connection.lock) { evict(userId, connection, CloseStatus.GOING_AWAY); }
            }
        });
    }
}
