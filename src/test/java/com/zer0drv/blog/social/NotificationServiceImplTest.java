package com.zer0drv.blog.social;

import com.zer0drv.blog.social.domain.Notification;
import com.zer0drv.blog.social.enums.NotificationType;
import com.zer0drv.blog.social.service.NotificationIntent;
import com.zer0drv.blog.social.service.NotificationMailService;
import com.zer0drv.blog.social.service.RealtimePushService;
import com.zer0drv.blog.social.service.impl.NotificationServiceImpl;
import com.zer0drv.blog.social.vo.NotificationVO;
import com.zer0drv.blog.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 通知落库规则纯单测：自发不发 / dedupe / commentId 维度 / 通道隔离（落库、WS 推送、邮件
 * 各自独立兜底，单通道失败不回滚主业务、不连坐其他通道）。
 *
 * @author Yoruhaki
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private UserService userService;

    @Mock
    private RealtimePushService realtimePushService;

    @Mock
    private NotificationMailService notificationMailService;

    private NotificationServiceImpl notificationService;

    @BeforeEach
    void setUp() {
        notificationService = spy(new NotificationServiceImpl(userService, realtimePushService, notificationMailService));
    }

    @Test
    void notify_selfAction_isSkipped() {
        notificationService.notify(NotificationIntent.articleLike(1L, 1L, 10L, "t"));

        verify(notificationService, never()).save(any());
        verify(notificationService, never()).count(any());
    }

    @Test
    void notify_dedupeHit_isSkipped() {
        doReturn(1L).when(notificationService).count(any());

        notificationService.notify(NotificationIntent.articleLike(1L, 2L, 10L, "t"));

        verify(notificationService, never()).save(any());
    }

    @Test
    void notify_articleLikeAndCommentLike_doNotDeduplicateEachOther() {
        // commentId 维度参与查重：同文章的文章赞与评论赞互不互斥，均可落库
        doReturn(0L).when(notificationService).count(any());
        doReturn(true).when(notificationService).save(any(Notification.class));

        notificationService.notify(NotificationIntent.articleLike(1L, 2L, 10L, "a"));
        notificationService.notify(NotificationIntent.commentLike(1L, 2L, 10L, 100L, "c"));

        verify(notificationService, times(2)).save(any(Notification.class));
    }

    @Test
    void notify_dedupeMiss_savesWithUnreadFlag() {
        doReturn(0L).when(notificationService).count(any());
        doReturn(true).when(notificationService).save(any(Notification.class));

        notificationService.notify(NotificationIntent.articleLike(1L, 2L, 10L, "title"));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationService).save(captor.capture());
        Notification saved = captor.getValue();
        assertEquals(1L, saved.getUserId());
        assertEquals(2L, saved.getActorId());
        assertEquals(NotificationType.ARTICLE_LIKE.name(), saved.getType());
        assertEquals(10L, saved.getArticleId());
        assertEquals((short) 0, saved.getReadFlag());
    }

    @Test
    void notify_withoutDedupe_savesDirectly() {
        doReturn(true).when(notificationService).save(any(Notification.class));

        notificationService.notify(NotificationIntent.commentReply(1L, 2L, 10L, 100L, "hi"));

        verify(notificationService, never()).count(any());
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationService).save(captor.capture());
        assertEquals("hi", captor.getValue().getSummary());
    }

    @Test
    void notify_saved_pushesRealtimeToRecipient() {
        doReturn(true).when(notificationService).save(any(Notification.class));

        notificationService.notify(NotificationIntent.commentReply(1L, 2L, 10L, 100L, "hi"));

        // 落库成功后向接收者推送 {"type":"notification","data":<NotificationVO>}
        ArgumentCaptor<NotificationVO> captor = ArgumentCaptor.forClass(NotificationVO.class);
        verify(realtimePushService).pushToUser(eq(1L), eq("notification"), captor.capture());
        NotificationVO pushed = captor.getValue();
        assertEquals(NotificationType.COMMENT_REPLY.name(), pushed.getType());
        assertEquals("hi", pushed.getSummary());
        assertEquals(10L, pushed.getArticleId());
    }

    @Test
    void notify_selfAction_noRealtimePush() {
        notificationService.notify(NotificationIntent.articleLike(1L, 1L, 10L, "t"));

        verify(realtimePushService, never()).pushToUser(any(), any(), any());
    }

    @Test
    void notify_persistenceFailure_isSwallowedAndSkipsDelivery() {
        doReturn(0L).when(notificationService).count(any());
        doThrow(new RuntimeException("db down")).when(notificationService).save(any(Notification.class));

        // 落库失败不回滚主业务：异常被吞掉；无落库实体则不再推送/邮件
        assertDoesNotThrow(() -> notificationService.notify(NotificationIntent.articleLike(1L, 2L, 10L, "t")));

        verify(realtimePushService, never()).pushToUser(any(), any(), any());
        verify(notificationMailService, never()).sendCommentMailAsync(any(), any(), any(), any(), any());
    }

    @Test
    void notify_pushFailure_mailStillDelivered() {
        // 通道隔离：WS 推送失败不连坐邮件（原同一 try 内会被连坐跳过）
        doReturn(true).when(notificationService).save(any(Notification.class));
        doThrow(new RuntimeException("ws down")).when(realtimePushService)
                .pushToUser(any(), any(), any());

        assertDoesNotThrow(() -> notificationService.notify(
                NotificationIntent.commentReply(1L, 2L, 10L, 100L, "hi")));

        verify(notificationMailService).sendCommentMailAsync(eq(1L), eq(NotificationType.COMMENT_REPLY),
                any(), eq(10L), eq("hi"));
    }

    @Test
    void notify_mailFailure_saveAndPushNotRolledBack() {
        // 通道隔离：邮件通道失败不影响已落库通知与已推送帧，异常不外抛
        doReturn(true).when(notificationService).save(any(Notification.class));
        doThrow(new RuntimeException("mail down")).when(notificationMailService)
                .sendCommentMailAsync(any(), any(), any(), any(), any());

        assertDoesNotThrow(() -> notificationService.notify(
                NotificationIntent.commentReply(1L, 2L, 10L, 100L, "hi")));

        verify(notificationService).save(any(Notification.class));
        verify(realtimePushService).pushToUser(eq(1L), eq("notification"), any());
    }

    @Test
    void notify_commentReply_triggersCommentMail() {
        // P0 §2.2：COMMENT_REPLY 落库+推送成功后触发评论邮件（异步，由 mail service 自兜底）
        doReturn(true).when(notificationService).save(any(Notification.class));

        notificationService.notify(NotificationIntent.commentReply(1L, 2L, 10L, 100L, "hi"));

        verify(notificationMailService).sendCommentMailAsync(eq(1L), eq(NotificationType.COMMENT_REPLY),
                any(), eq(10L), eq("hi"));
    }

    @Test
    void notify_articleLike_doesNotTriggerCommentMail() {
        // P0 §2.2：仅 COMMENT_REPLY/MENTION 触发邮件，点赞等类型不触发
        doReturn(0L).when(notificationService).count(any());
        doReturn(true).when(notificationService).save(any(Notification.class));

        notificationService.notify(NotificationIntent.articleLike(1L, 2L, 10L, "t"));

        verify(notificationMailService, never()).sendCommentMailAsync(any(), any(), any(), any(), any());
    }
}
