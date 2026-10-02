package com.zer0drv.blog.social;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zer0drv.blog.admin.service.SensitiveWordService;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.social.domain.PrivateMessage;
import com.zer0drv.blog.social.enums.NotificationType;
import com.zer0drv.blog.social.service.NotificationService;
import com.zer0drv.blog.social.service.RealtimePushService;
import com.zer0drv.blog.social.service.impl.MessageServiceImpl;
import com.zer0drv.blog.user.service.UserService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 私信已读纯单测：markRead 除把 private_message 标记已读外，
 * 还必须同步把来自该会话对方（peer=发信人=通知触发人）的 PRIVATE_MESSAGE 通知标记已读，
 * 否则通知铃铛未读数不减少。MP 继承方法（update）用 spy + doReturn 桩掉。
 *
 * @author Yoruhaki
 */
@ExtendWith(MockitoExtension.class)
class MessageServiceImplTest {

    @Mock
    private UserService userService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private SensitiveWordService sensitiveWordService;

    @Mock
    private RealtimePushService realtimePushService;

    private MessageServiceImpl messageService;

    @BeforeAll
    static void initTableInfo() {
        // 纯单测无 MyBatis 容器：手动初始化实体 TableInfo，供 LambdaUpdateWrapper 解析列名
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, PrivateMessage.class);
    }

    @BeforeEach
    void setUp() {
        messageService = spy(new MessageServiceImpl(
                userService, notificationService, sensitiveWordService, realtimePushService));
    }

    private static Jwt jwtOf(long userId) {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(Long.toString(userId));
        return jwt;
    }

    @Test
    void markRead_marksMessagesAndSyncsPeerPrivateMessageNotifications() {
        doReturn(true).when(messageService).update(any());

        messageService.markRead(3L, jwtOf(10L));

        // 私信表：接收者=我、发送者=peer、未读 → 置已读
        ArgumentCaptor<LambdaUpdateWrapper<PrivateMessage>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(messageService).update(captor.capture());
        LambdaUpdateWrapper<PrivateMessage> wrapper = captor.getValue();
        String setSql = wrapper.getSqlSet();
        assertTrue(setSql.contains("read_flag"), setSql);
        String whereSql = wrapper.getSqlSegment();
        assertTrue(whereSql.contains("receiver_id"), whereSql);
        assertTrue(whereSql.contains("sender_id"), whereSql);
        assertTrue(whereSql.contains("read_flag"), whereSql);
        // 通知同步：接收者=我、类型=PRIVATE_MESSAGE、触发人=peer（发信人）
        verify(notificationService).markReadByTypeAndActor(10L, NotificationType.PRIVATE_MESSAGE, 3L);
    }

    @Test
    void markRead_selfPeer_throwsAndSkipsNotificationSync() {
        assertThrows(BusinessException.class, () -> messageService.markRead(10L, jwtOf(10L)));

        verify(notificationService, never()).markReadByTypeAndActor(any(), any(), any());
    }

    @Test
    void markRead_nullPeer_throwsAndSkipsNotificationSync() {
        assertThrows(BusinessException.class, () -> messageService.markRead(null, jwtOf(10L)));

        verify(notificationService, never()).markReadByTypeAndActor(any(), any(), any());
    }
}
