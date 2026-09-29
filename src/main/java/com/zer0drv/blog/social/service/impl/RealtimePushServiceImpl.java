package com.zer0drv.blog.social.service.impl;

import com.zer0drv.blog.social.service.RealtimePushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * RealtimePushService 默认实现：内存 session 表（单机部署足够；
 * 若未来多实例部署需换成 Redis pub/sub 广播）。
 *
 * @author Yoruhaki
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RealtimePushServiceImpl implements RealtimePushService {

    /**
     * 在线连接表：userId → 该用户的全部 WebSocket session（同一用户可多端在线）
     */
    private final Map<Long, CopyOnWriteArraySet<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    @Override
    public void register(Long userId, WebSocketSession session) {
        if (Objects.isNull(userId) || Objects.isNull(session)) {
            return;
        }
        sessions.computeIfAbsent(userId, _ -> new CopyOnWriteArraySet<>()).add(session);
        log.debug("WebSocket session 上线：userId={}, sessionId={}", userId, session.getId());
    }

    @Override
    public void unregister(Long userId, WebSocketSession session) {
        if (Objects.isNull(userId) || Objects.isNull(session)) {
            return;
        }
        CopyOnWriteArraySet<WebSocketSession> userSessions = sessions.get(userId);
        if (Objects.nonNull(userSessions)) {
            userSessions.remove(session);
            if (userSessions.isEmpty()) {
                // 空桶顺手摘除（值相等才删，避免误删并发新注册产生的桶）
                sessions.remove(userId, userSessions);
            }
            log.debug("WebSocket session 下线：userId={}, sessionId={}", userId, session.getId());
        }
    }

    @Override
    public void pushToUser(Long userId, String type, Object payload) {
        try {
            CopyOnWriteArraySet<WebSocketSession> userSessions = sessions.get(userId);
            if (Objects.isNull(userSessions) || userSessions.isEmpty()) {
                return;
            }
            // LinkedHashMap 保证帧字段顺序固定为 {"type": ..., "data": ...}
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("type", type);
            frame.put("data", payload);
            TextMessage message = new TextMessage(objectMapper.writeValueAsString(frame));
            for (WebSocketSession session : userSessions) {
                sendOrEvict(userId, session, message);
            }
        } catch (Exception e) {
            // 推送是旁路能力：任何失败仅记日志，绝不抛给主业务
            log.warn("实时推送失败：userId={}, type={}, reason={}", userId, type, e.getMessage());
        }
    }

    /**
     * 发送单帧；session 已关闭或发送异常时从表中移除（死 session 清理）。
     * WebSocketSession 非线程安全，同一 session 的并发发送需串行化。
     */
    private void sendOrEvict(Long userId, WebSocketSession session, TextMessage message) {
        try {
            if (!session.isOpen()) {
                unregister(userId, session);
                return;
            }
            synchronized (session) {
                session.sendMessage(message);
            }
        } catch (Exception e) {
            log.warn("WebSocket 发送失败，移除死 session：userId={}, sessionId={}, reason={}",
                    userId, session.getId(), e.getMessage());
            unregister(userId, session);
        }
    }
}
