package com.zer0drv.blog.social;

import com.zer0drv.blog.social.enums.NotificationType;
import com.zer0drv.blog.social.service.NotificationIntent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NotificationIntent 值对象规则：紧凑构造器校验（接收人/类型必填、summary null→""）、
 * 各静态工厂的类型/上下文字段映射，以及内建 dedupe 策略
 * （点赞 / 关注为 true，回复 / @ / 私信为 false）。
 *
 * @author Yoruhaki
 */
class NotificationIntentTest {

    @Test
    void factories_dedupePolicy_matchesTypeMatrix() {
        // 幂等防重组：取消再操作不重复打扰
        assertTrue(NotificationIntent.articleLike(1L, 2L, 10L, "t").dedupe());
        assertTrue(NotificationIntent.commentLike(1L, 2L, 10L, 100L, "c").dedupe());
        assertTrue(NotificationIntent.follow(1L, 2L).dedupe());
        // 每次必发组
        assertFalse(NotificationIntent.commentReply(1L, 2L, 10L, 100L, "s").dedupe());
        assertFalse(NotificationIntent.mention(1L, 2L, 10L, 100L, "s").dedupe());
        assertFalse(NotificationIntent.privateMessage(1L, 2L, "s").dedupe());
        // 违规强删：每次代删都必发，作者需要感知每一篇被删
        assertFalse(NotificationIntent.articleForceDeleted(1L, 2L, "t").dedupe());
    }

    @Test
    void factories_mapTypeAndContext() {
        NotificationIntent reply = NotificationIntent.commentReply(1L, 2L, 10L, 100L, "s");
        assertEquals(NotificationType.COMMENT_REPLY, reply.type());
        assertEquals(1L, reply.recipientId());
        assertEquals(2L, reply.actorId());
        assertEquals(10L, reply.articleId());
        assertEquals(100L, reply.commentId());
        assertEquals("s", reply.summary());

        NotificationIntent mention = NotificationIntent.mention(1L, 2L, 10L, 100L, "s");
        assertEquals(NotificationType.MENTION, mention.type());
        assertEquals(100L, mention.commentId());

        NotificationIntent articleLike = NotificationIntent.articleLike(1L, 2L, 10L, "title");
        assertEquals(NotificationType.ARTICLE_LIKE, articleLike.type());
        assertEquals("title", articleLike.summary());
        assertNull(articleLike.commentId());

        NotificationIntent commentLike = NotificationIntent.commentLike(1L, 2L, 10L, 100L, "c");
        assertEquals(NotificationType.COMMENT_LIKE, commentLike.type());
        assertEquals(100L, commentLike.commentId());

        NotificationIntent follow = NotificationIntent.follow(1L, 2L);
        assertEquals(NotificationType.FOLLOW, follow.type());
        assertEquals("", follow.summary());
        assertNull(follow.articleId());
        assertNull(follow.commentId());

        NotificationIntent pm = NotificationIntent.privateMessage(1L, 2L, "hello");
        assertEquals(NotificationType.PRIVATE_MESSAGE, pm.type());
        assertEquals("hello", pm.summary());
        assertNull(pm.articleId());
        assertNull(pm.commentId());

        // 违规强删：summary 携带被删文章标题；articleId / commentId 恒为 null（行已物理删除）
        NotificationIntent forceDeleted = NotificationIntent.articleForceDeleted(7L, 9L, "被删标题");
        assertEquals(NotificationType.ARTICLE_FORCE_DELETED, forceDeleted.type());
        assertEquals(7L, forceDeleted.recipientId());
        assertEquals(9L, forceDeleted.actorId());
        assertEquals("被删标题", forceDeleted.summary());
        assertNull(forceDeleted.articleId());
        assertNull(forceDeleted.commentId());
    }

    @Test
    void constructor_nullRecipient_rejected() {
        assertThrows(NullPointerException.class,
                () -> NotificationIntent.commentReply(null, 2L, 10L, 100L, "s"));
    }

    @Test
    void constructor_nullType_rejected() {
        assertThrows(NullPointerException.class,
                () -> new NotificationIntent(1L, null, 2L, 10L, 100L, "s", false));
    }

    @Test
    void constructor_nullSummary_normalizedToEmpty() {
        NotificationIntent intent = NotificationIntent.privateMessage(1L, 2L, null);

        assertEquals("", intent.summary());
    }
}
