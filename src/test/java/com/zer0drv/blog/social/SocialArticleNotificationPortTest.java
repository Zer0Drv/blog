package com.zer0drv.blog.social;

import com.zer0drv.blog.social.enums.NotificationType;
import com.zer0drv.blog.social.service.NotificationIntent;
import com.zer0drv.blog.social.service.NotificationService;
import com.zer0drv.blog.social.service.impl.SocialArticleNotificationPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SocialArticleNotificationPort 适配器测试：article.api 端口参数（作者 / 操作者 / 标题）
 * 到 NotificationIntent 的组装映射，随后经 ObjectProvider 懒解析委托 NotificationService.notify。
 *
 * @author Yoruhaki
 */
@ExtendWith(MockitoExtension.class)
class SocialArticleNotificationPortTest {

    @Mock
    private NotificationService notificationService;
    @Mock
    private ObjectProvider<NotificationService> notificationServiceProvider;

    @Test
    void notifyAuthorForceDeleted_assemblesIntentAndDelegates() {
        when(notificationServiceProvider.getObject()).thenReturn(notificationService);
        SocialArticleNotificationPort port = new SocialArticleNotificationPort(notificationServiceProvider);

        port.notifyAuthorForceDeleted(7L, 9L, "被删标题");

        ArgumentCaptor<NotificationIntent> captor = ArgumentCaptor.forClass(NotificationIntent.class);
        verify(notificationService).notify(captor.capture());
        NotificationIntent intent = captor.getValue();
        assertEquals(7L, intent.recipientId());
        assertEquals(9L, intent.actorId());
        assertEquals(NotificationType.ARTICLE_FORCE_DELETED, intent.type());
        assertEquals("被删标题", intent.summary());
        assertNull(intent.articleId());
        assertNull(intent.commentId());
        assertFalse(intent.dedupe());
    }
}
