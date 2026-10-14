package com.zer0drv.blog.subscription;
import com.zer0drv.blog.subscription.repository.SubscriptionRepository;
import com.zer0drv.blog.subscription.service.SubscriptionWorker;

import com.zer0drv.blog.article.api.ArticleCatalog;
import com.zer0drv.blog.article.api.ArticleFeedEntry;
import com.zer0drv.blog.site.service.SiteConfigService;
import com.zer0drv.blog.subscription.api.SubscriptionNotificationPort;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class SubscriptionWorkerTest {
    @Mock SubscriptionRepository repository;
    @Mock UserService users;
    @Mock ArticleCatalog articles;
    @Mock SubscriptionNotificationPort notifications;
    @Mock SiteConfigService site;
    @Mock ObjectProvider<JavaMailSender> mail;
    @Mock JavaMailSender sender;
    SubscriptionWorker worker;
    User reader;
    ArticleFeedEntry article;

    @BeforeEach
    void setup() {
        worker = new SubscriptionWorker(repository, users, articles, notifications, site, mail);
        reader = new User(); reader.setId(7L); reader.setEmail("reader@example.com");
        reader.setArticleNotifyEnabled((short) 1); reader.setArticleEmailNotifyEnabled((short) 1);
        reader.setArticleUnsubscribeToken("a".repeat(32));
        article = new ArticleFeedEntry(10L, 1L, "新文章", "摘要", "new-post", LocalDateTime.now(), LocalDateTime.now());
        when(repository.pending(any())).thenReturn(List.of(new SubscriptionRepository.Publication(10L, LocalDateTime.now())));
        when(repository.claim(eq(10L), any(), anyString())).thenReturn(true);
        when(repository.heartbeat(eq(10L), anyString())).thenReturn(true);
        when(articles.findVisibleFeedEntry(10L)).thenReturn(Optional.of(article));
        when(users.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(reader));
    }

    @Test
    void smtpFailureRetriesMailWithoutRepeatingNotification() {
        when(users.getById(7L)).thenReturn(reader);
        when(repository.delivery(10L, 7L)).thenReturn(new SubscriptionRepository.Delivery("DONE", "FAILED", 1));
        when(repository.reserveEmail(7L)).thenReturn(true);
        when(mail.getIfAvailable()).thenReturn(sender);
        when(site.getValue(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        doThrow(new IllegalStateException("smtp down")).when(sender).send(any(SimpleMailMessage.class));
        worker.processPending();
        verifyNoInteractions(notifications);
        verify(repository).record(10L, 7L, "DONE", "FAILED");
        verify(repository).finish(eq(10L), anyString(), eq("PENDING"));
    }

    @Test
    void emailHasCanonicalArticleLinkAndCapabilityUnsubscribe() {
        when(users.getById(7L)).thenReturn(reader);
        when(repository.delivery(10L, 7L)).thenReturn(new SubscriptionRepository.Delivery("PENDING", "PENDING", 0));
        when(notifications.deliver(7L, article)).thenReturn(true);
        when(repository.reserveEmail(7L)).thenReturn(true);
        when(mail.getIfAvailable()).thenReturn(sender);
        when(site.getValue(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        worker.processPending();
        var message = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(message.capture());
        assertTrue(message.getValue().getText().contains("/article/new-post"));
        assertTrue(message.getValue().getText().contains("/subscription/unsubscribe?token=" + "a".repeat(32)));
        verify(repository).record(10L, 7L, "DONE", "SENT");
    }

    @Test
    void exhaustedRetryDoesNotContactMailOrNotifyAgain() {
        when(repository.delivery(10L, 7L)).thenReturn(new SubscriptionRepository.Delivery("FAILED", "FAILED", 3));
        worker.processPending();
        verifyNoInteractions(mail, notifications);
        verify(repository, never()).record(anyLong(), anyLong(), anyString(), anyString());
        verify(repository).finish(eq(10L), anyString(), eq("DONE"));
    }
}
