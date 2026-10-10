package com.zer0drv.blog.social;

import com.zer0drv.blog.article.api.ArticleCatalog;
import com.zer0drv.blog.article.api.ArticleRef;
import com.zer0drv.blog.site.service.SiteConfigService;
import com.zer0drv.blog.social.enums.NotificationType;
import com.zer0drv.blog.social.service.impl.NotificationMailServiceImpl;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NotificationMailServiceImpl 纯单测（P0 §2.2 邮件跳过条件矩阵）：
 * 用户不存在 / 开关关闭 / 邮箱为空或 @oauth.local / 无 JavaMailSender（dev-fallback）/ 正常发送 / 异常吞掉。
 * 单测无 Spring 代理，@Async 方法同步执行便于断言。
 *
 * @author Yoruhaki
 */
@ExtendWith(MockitoExtension.class)
class NotificationMailServiceImplTest {

    @Mock
    private UserService userService;
    @Mock
    private ArticleCatalog articleCatalog;
    @Mock
    private ObjectProvider<JavaMailSender> mailSenderProvider;
    @Mock
    private ObjectProvider<SiteConfigService> siteConfigServiceProvider;
    @Mock
    private JavaMailSender mailSender;
    @Mock
    private SiteConfigService siteConfigService;

    private NotificationMailServiceImpl mailService;

    @BeforeEach
    void setUp() {
        mailService = new NotificationMailServiceImpl(
                userService, articleCatalog, mailSenderProvider, siteConfigServiceProvider);
    }

    private static User user(long id, String email, Short emailNotifyEnabled) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setEmailNotifyEnabled(emailNotifyEnabled);
        return user;
    }

    @Test
    void send_userNotExist_skips() {
        when(userService.getById(9L)).thenReturn(null);

        mailService.sendCommentMailAsync(9L, NotificationType.COMMENT_REPLY, "甲", 10L, "摘要");

        verify(mailSenderProvider, never()).getIfAvailable();
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void send_notifyDisabled_skips() {
        when(userService.getById(9L)).thenReturn(user(9L, "a@example.com", (short) 0));

        mailService.sendCommentMailAsync(9L, NotificationType.COMMENT_REPLY, "甲", 10L, "摘要");

        verify(mailSenderProvider, never()).getIfAvailable();
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void send_blankEmail_skips() {
        when(userService.getById(9L)).thenReturn(user(9L, " ", (short) 1));

        mailService.sendCommentMailAsync(9L, NotificationType.COMMENT_REPLY, "甲", 10L, "摘要");

        verify(mailSenderProvider, never()).getIfAvailable();
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void send_oauthPlaceholderEmail_skips() {
        when(userService.getById(9L)).thenReturn(user(9L, "12345@oauth.local", (short) 1));

        mailService.sendCommentMailAsync(9L, NotificationType.COMMENT_REPLY, "甲", 10L, "摘要");

        verify(mailSenderProvider, never()).getIfAvailable();
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void send_noMailSender_devFallbackSkips() {
        // 未配置 SMTP（IT/dev 形态）：走 dev-fallback 日志，不抛异常
        when(userService.getById(9L)).thenReturn(user(9L, "a@example.com", (short) 1));
        when(mailSenderProvider.getIfAvailable()).thenReturn(null);

        assertDoesNotThrow(() -> mailService.sendCommentMailAsync(
                9L, NotificationType.COMMENT_REPLY, "甲", 10L, "摘要"));

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void send_happyPath_sendsWithSiteConfig() {
        when(userService.getById(9L)).thenReturn(user(9L, "a@example.com", (short) 1));
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
        when(siteConfigServiceProvider.getIfAvailable()).thenReturn(siteConfigService);
        when(siteConfigService.getValue("site.name", "Blog")).thenReturn("我的站");
        when(siteConfigService.getValue("site.base_url", "http://localhost:5173")).thenReturn("https://blog.example.com");
        when(articleCatalog.findRef(10L)).thenReturn(Optional.of(new ArticleRef(10L, 1L, "标题文")));

        mailService.sendCommentMailAsync(9L, NotificationType.COMMENT_REPLY, "评论人甲", 10L, "写得好");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        assertEquals("【我的站】你的文章有新回复", message.getSubject());
        assertEquals("a@example.com", message.getTo()[0]);
        assertTrue(message.getText().contains("评论人甲"));
        assertTrue(message.getText().contains("标题文"));
        assertTrue(message.getText().contains("写得好"));
        assertTrue(message.getText().contains("https://blog.example.com/article/10"));
    }

    @Test
    void send_siteConfigAbsent_usesDefaults() {
        // 容器无 SiteConfigService 实现（合并前形态）：site.name/base_url 走默认值兜底
        when(userService.getById(9L)).thenReturn(user(9L, "a@example.com", null));
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
        lenient().when(siteConfigServiceProvider.getIfAvailable()).thenReturn(null);
        when(articleCatalog.findRef(10L)).thenReturn(Optional.empty());

        mailService.sendCommentMailAsync(9L, NotificationType.MENTION, null, 10L, "摘要");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertEquals("【Blog】你的文章有新回复", captor.getValue().getSubject());
        assertTrue(captor.getValue().getText().contains("http://localhost:5173/article/10"));
    }

    @Test
    void send_mailSendThrows_isSwallowed() {
        when(userService.getById(9L)).thenReturn(user(9L, "a@example.com", (short) 1));
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
        lenient().when(siteConfigServiceProvider.getIfAvailable()).thenReturn(null);
        when(articleCatalog.findRef(10L)).thenReturn(Optional.empty());
        doThrow(new RuntimeException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));

        // 邮件失败不抛出、不回滚主业务
        assertDoesNotThrow(() -> mailService.sendCommentMailAsync(
                9L, NotificationType.COMMENT_REPLY, "甲", 10L, "摘要"));
    }
}
