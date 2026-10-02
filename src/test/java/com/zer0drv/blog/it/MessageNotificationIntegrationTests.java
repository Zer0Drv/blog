package com.zer0drv.blog.it;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 私信已读联动通知已读回归测试（bug：读完会话后 PRIVATE_MESSAGE 通知仍保持未读，
 * 铃铛未读数不减少）。真实 HTTP 路由 + H2：
 * ① 读完与 A 的会话后，来自 A 的 PRIVATE_MESSAGE 未读通知全部变已读；
 * ② 来自 B 的私信通知不受影响；
 * ③ 其他类型通知（FOLLOW）不受影响；重复标记幂等。
 *
 * @author Yoruhaki
 */
class MessageNotificationIntegrationTests extends IntegrationTestSupport {

    @Test
    void markConversationRead_syncsPeerPrivateMessageNotificationsOnly() throws Exception {
        String alice = unique("it_msg_alice_");
        String bob = unique("it_msg_bob_");
        String me = unique("it_msg_me_");
        long aliceId = seedUser(alice, "私信甲", "USER");
        long bobId = seedUser(bob, "私信乙", "USER");
        long meId = seedUser(me, "私信我", "USER");

        // A 发 2 条、B 发 1 条私信给我：每条落一条 PRIVATE_MESSAGE 未读通知
        String aliceBearer = bearerOf(alice);
        sendMessage(aliceBearer, meId, "你好-1");
        sendMessage(aliceBearer, meId, "你好-2");
        sendMessage(bearerOf(bob), meId, "你好-3");
        // 其他类型未读通知（来自 B 的 FOLLOW），不应被私信已读联动影响
        jdbcTemplate.update("INSERT INTO notification (user_id, type, actor_id, summary) VALUES (?,?,?,?)",
                meId, "FOLLOW", bobId, "关注了你");

        String meBearer = bearerOf(me);
        assertUnreadCount(meBearer, 4);

        // 读完与 A 的会话
        markConversationRead(meBearer, aliceId);

        // ① 未读数 4 → 2；来自 A 的 PRIVATE_MESSAGE 通知全部已读
        assertUnreadCount(meBearer, 2);
        assertEquals(0, countNotifications(meId, "PRIVATE_MESSAGE", aliceId, 0));
        // ② 来自 B 的私信通知不受影响（仍未读）
        assertEquals(1, countNotifications(meId, "PRIVATE_MESSAGE", bobId, 0));
        // ③ FOLLOW 类型通知不受影响（仍未读）
        assertEquals(1, countNotifications(meId, "FOLLOW", bobId, 0));

        // 幂等：重复标记同会话已读仍成功，未读数不变
        markConversationRead(meBearer, aliceId);
        assertUnreadCount(meBearer, 2);
    }

    private void sendMessage(String bearerValue, long receiverId, String content) throws Exception {
        mockMvc.perform(post("/messages")
                        .header(HttpHeaders.AUTHORIZATION, bearerValue)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverId\":" + receiverId + ",\"content\":\"" + content + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
    }

    private void markConversationRead(String bearerValue, long peerId) throws Exception {
        mockMvc.perform(put("/messages/read")
                        .param("peerId", String.valueOf(peerId))
                        .header(HttpHeaders.AUTHORIZATION, bearerValue))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
    }

    private void assertUnreadCount(String bearerValue, long expected) throws Exception {
        mockMvc.perform(get("/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, bearerValue))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(expected));
    }

    private int countNotifications(long userId, String type, long actorId, int readFlag) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE user_id=? AND type=? AND actor_id=? AND read_flag=?",
                Integer.class, userId, type, actorId, readFlag);
        return count == null ? 0 : count;
    }
}
