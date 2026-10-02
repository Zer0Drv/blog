package com.zer0drv.blog.social;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zer0drv.blog.social.domain.Notification;
import com.zer0drv.blog.social.enums.NotificationType;
import com.zer0drv.blog.social.service.NotificationMailService;
import com.zer0drv.blog.social.service.RealtimePushService;
import com.zer0drv.blog.social.service.impl.NotificationServiceImpl;
import com.zer0drv.blog.user.service.UserService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * markReadByTypeAndActor 纯单测：更新条件必须精确限定 接收者+类型+触发人+未读，
 * 保证只把来自该 actor 的该类型未读通知置已读（其他 actor / 其他类型不受影响），
 * 且 SET read_flag=READ；幂等（无匹配行时 update 0 行静默成功）。
 * MP 继承方法（update）用 spy + doReturn 桩掉，TableInfo 手动初始化供列名解析。
 *
 * @author Yoruhaki
 */
@ExtendWith(MockitoExtension.class)
class NotificationMarkReadSyncTest {

    @Mock
    private UserService userService;

    @Mock
    private RealtimePushService realtimePushService;

    @Mock
    private NotificationMailService notificationMailService;

    private NotificationServiceImpl notificationService;

    @BeforeAll
    static void initTableInfo() {
        // 纯单测无 MyBatis 容器：手动初始化实体 TableInfo，供 LambdaUpdateWrapper 解析列名
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Notification.class);
    }

    @BeforeEach
    void setUp() {
        notificationService = spy(new NotificationServiceImpl(userService, realtimePushService, notificationMailService));
    }

    @Test
    void markReadByTypeAndActor_updatesOnlyMatchingUnread() {
        doReturn(true).when(notificationService).update(any());

        notificationService.markReadByTypeAndActor(1L, NotificationType.PRIVATE_MESSAGE, 2L);

        ArgumentCaptor<LambdaUpdateWrapper<Notification>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(notificationService).update(captor.capture());
        LambdaUpdateWrapper<Notification> wrapper = captor.getValue();
        String setSql = wrapper.getSqlSet();
        assertTrue(setSql.contains("read_flag"), setSql);
        String whereSql = wrapper.getSqlSegment();
        assertTrue(whereSql.contains("user_id"), whereSql);
        assertTrue(whereSql.contains("type"), whereSql);
        assertTrue(whereSql.contains("actor_id"), whereSql);
        assertTrue(whereSql.contains("read_flag"), whereSql);
        Collection<Object> params = wrapper.getParamNameValuePairs().values();
        // WHERE：接收者=1、类型=PRIVATE_MESSAGE、触发人=2、read_flag=UNREAD(0)；SET：read_flag=READ(1)
        assertTrue(params.contains(1L), params.toString());
        assertTrue(params.contains(NotificationType.PRIVATE_MESSAGE.name()), params.toString());
        assertTrue(params.contains(2L), params.toString());
        assertTrue(params.contains((short) 0), params.toString());
        assertTrue(params.contains((short) 1), params.toString());
    }
}
