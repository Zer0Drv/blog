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
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 本机连接表；每帧检查共享黑名单，空闲连接每秒复检。
 * 发送、检查和吊销关闭共用 session 锁：本机吊销返回时，已进入的发送已结束。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RealtimePushServiceImpl implements RealtimePushService {

    private final Map<Long, CopyOnWriteArraySet<WebSocketSession>> sessions = new ConcurrentHashMap<>();
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
        sessions.compute(userId, (_, bucket) -> {
            if (bucket == null) bucket = new CopyOnWriteArraySet<>();
            bucket.add(session);
            return bucket;
        });
        synchronized (session) {
            validateOrEvict(userId, session);
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
            for (WebSocketSession session : bucket) sendOrEvict(userId, session, message);
        } catch (Exception e) {
            // 不记录异常消息，避免第三方异常带入原始凭据或消息正文。
            log.warn("实时推送失败：userId={}, type={}", userId, type);
        }
    }

    @Override
    public void pong(Long userId, WebSocketSession session) {
        if (userId != null && session != null) sendOrEvict(userId, session, new TextMessage("pong"));
    }

    private void sendOrEvict(Long userId, WebSocketSession session, TextMessage message) {
        synchronized (session) {
            if (!validateOrEvict(userId, session)) return;
            try {
                session.sendMessage(message);
            } catch (Exception e) {
                evict(userId, session, CloseStatus.SERVER_ERROR);
            }
        }
    }

    // 调用方持有 session 锁。即使发送拿到了旧 bucket 快照，移除后也不能继续发送。
    private boolean validateOrEvict(Long userId, WebSocketSession session) {
        var bucket = sessions.get(userId);
        if (bucket == null || !bucket.contains(session)) return false;
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
        evict(userId, session, CloseStatus.POLICY_VIOLATION);
        return false;
    }

    @EventListener
    public void onTokenRevoked(TokenRevoked event) {
        sessions.forEach((userId, bucket) -> {
            for (WebSocketSession session : bucket) {
                if (event.jti() != null && event.jti().equals(session.getAttributes().get(WebSocketConfig.ATTR_TOKEN_ID))) {
                    synchronized (session) { evict(userId, session, CloseStatus.POLICY_VIOLATION); }
                }
            }
        });
    }

    @EventListener
    public void onUserTokensRevoked(UserTokensRevoked event) {
        var bucket = sessions.get(event.userId());
        if (bucket == null) return;
        for (WebSocketSession session : bucket) {
            synchronized (session) { evict(event.userId(), session, CloseStatus.POLICY_VIOLATION); }
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
            for (WebSocketSession session : bucket) {
                synchronized (session) { validateOrEvict(userId, session); }
            }
        });
    }

    private void evict(Long userId, WebSocketSession session, CloseStatus status) {
        unregister(userId, session);
        close(session, status);
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
            for (WebSocketSession session : bucket) {
                synchronized (session) { evict(userId, session, CloseStatus.GOING_AWAY); }
            }
        });
    }
}
