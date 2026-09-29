package com.zer0drv.blog.social.ws;

import com.zer0drv.blog.config.WebSocketConfig;
import com.zer0drv.blog.social.service.RealtimePushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Objects;

/**
 * 私信+通知推送的 WebSocket 端点（/ws）。
 * 只负责连接生命周期：建立时按握手拦截器写入 attributes 的 userId 注册 session，
 * 关闭时清理。客户端帧内容忽略（预留 "ping" → "pong" 心跳应答），不做频道订阅。
 *
 * @author Yoruhaki
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyWebSocketHandler extends TextWebSocketHandler {

    private final RealtimePushService realtimePushService;

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) {
        Long userId = userIdOf(session);
        if (Objects.nonNull(userId)) {
            realtimePushService.register(userId, session);
        }
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message)
            throws Exception {
        // 客户端帧内容忽略；预留文本心跳："ping" → "pong"
        if ("ping".equalsIgnoreCase(message.getPayload().trim())) {
            session.sendMessage(new TextMessage("pong"));
        }
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
        Long userId = userIdOf(session);
        if (Objects.nonNull(userId)) {
            realtimePushService.unregister(userId, session);
        }
    }

    /**
     * 从握手 attributes 取 userId（JwtHandshakeInterceptor 在握手成功时写入，必然存在）
     */
    private Long userIdOf(WebSocketSession session) {
        Object attr = session.getAttributes().get(WebSocketConfig.ATTR_USER_ID);
        return attr instanceof Long userId ? userId : null;
    }
}
