package com.zer0drv.blog.social.service;

import org.springframework.web.socket.WebSocketSession;

/**
 * WebSocket 实时推送：按用户维护在线 session 表，向指定用户的所有连接
 * 推送 JSON 帧 {@code {"type": "...", "data": {...}}}。
 * 实现内部已做全量 try/catch 兜底，调用方无需再包，推送失败绝不影响主业务。
 *
 * @author Yoruhaki
 */
public interface RealtimePushService {

    /**
     * 连接建立后登记 session（由 handler 回调）
     */
    void register(Long userId, WebSocketSession session);

    /**
     * 连接关闭后移除 session（由 handler 回调）
     */
    void unregister(Long userId, WebSocketSession session);

    /**
     * 向某用户的全部在线 session 推送一条消息；无在线 session 时静默跳过。
     * 发送失败的死 session 会被移除。
     */
    void pushToUser(Long userId, String type, Object payload);
}
