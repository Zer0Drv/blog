package com.zer0drv.blog.social;

import com.zer0drv.blog.social.service.impl.RealtimePushServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RealtimePushService 纯单测：session 注册/推送帧结构/死 session 清理/多端在线。
 * WebSocketSession 全部用 Mockito mock，不依赖真实 WS 服务。
 *
 * @author Yoruhaki
 */
class RealtimePushServiceTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    private RealtimePushServiceImpl pushService;

    @BeforeEach
    void setUp() {
        pushService = new RealtimePushServiceImpl(objectMapper);
    }

    /**
     * 构造一个默认在线的 mock session
     */
    private WebSocketSession openSession(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        lenient().when(session.getId()).thenReturn(id);
        lenient().when(session.isOpen()).thenReturn(true);
        return session;
    }

    @Test
    void registerAndPush_sendsJsonFrameToSession() throws Exception {
        WebSocketSession session = openSession("s1");
        pushService.register(1L, session);

        pushService.pushToUser(1L, "private_message", Map.of("id", 100, "content", "你好"));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        JsonNode frame = objectMapper.readTree(captor.getValue().getPayload());
        assertEquals("private_message", frame.get("type").asText());
        assertEquals(100, frame.get("data").get("id").asInt());
        assertEquals("你好", frame.get("data").get("content").asText());
        // 帧字段顺序固定为 type 在前、data 在后（前端契约）
        assertTrue(captor.getValue().getPayload().startsWith("{\"type\":"));
    }

    @Test
    void push_multipleSessionsOfOneUser_allReceive() throws Exception {
        WebSocketSession s1 = openSession("s1");
        WebSocketSession s2 = openSession("s2");
        pushService.register(1L, s1);
        pushService.register(1L, s2);

        pushService.pushToUser(1L, "notification", Map.of("id", 1));

        verify(s1).sendMessage(any(TextMessage.class));
        verify(s2).sendMessage(any(TextMessage.class));
    }

    @Test
    void push_deadSessionOnSendFailure_isEvicted() throws Exception {
        WebSocketSession dead = openSession("dead");
        doThrow(new IOException("connection reset")).when(dead).sendMessage(any());
        WebSocketSession alive = openSession("alive");
        pushService.register(1L, dead);
        pushService.register(1L, alive);

        // 第一次推送：dead 发送失败被移除，alive 正常收到；异常不外抛
        assertDoesNotThrow(() -> pushService.pushToUser(1L, "notification", Map.of("id", 1)));
        verify(dead, times(1)).sendMessage(any());
        verify(alive, times(1)).sendMessage(any());

        // 第二次推送：dead 已被清理，不再尝试发送
        pushService.pushToUser(1L, "notification", Map.of("id", 2));
        verify(dead, times(1)).sendMessage(any());
        verify(alive, times(2)).sendMessage(any());
    }

    @Test
    void push_closedSession_isEvictedWithoutSend() throws Exception {
        WebSocketSession closed = openSession("closed");
        when(closed.isOpen()).thenReturn(false);
        pushService.register(1L, closed);

        pushService.pushToUser(1L, "notification", Map.of("id", 1));

        verify(closed, never()).sendMessage(any());
        // 已移除：再次推送也不发送
        pushService.pushToUser(1L, "notification", Map.of("id", 2));
        verify(closed, never()).sendMessage(any());
    }

    @Test
    void unregister_thenPush_noSend() throws Exception {
        WebSocketSession session = openSession("s1");
        pushService.register(1L, session);
        pushService.unregister(1L, session);

        pushService.pushToUser(1L, "notification", Map.of("id", 1));

        verify(session, never()).sendMessage(any());
    }

    @Test
    void push_userWithoutSession_isNoop() {
        assertDoesNotThrow(() -> pushService.pushToUser(999L, "notification", Map.of("id", 1)));
    }

    @Test
    void push_sessionsAreIsolatedByUser() throws Exception {
        WebSocketSession s1 = openSession("s1");
        WebSocketSession s2 = openSession("s2");
        pushService.register(1L, s1);
        pushService.register(2L, s2);

        pushService.pushToUser(1L, "private_message", Map.of("id", 1));

        verify(s1).sendMessage(any(TextMessage.class));
        verify(s2, never()).sendMessage(any());
    }
}
