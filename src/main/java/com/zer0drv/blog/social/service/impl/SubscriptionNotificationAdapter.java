package com.zer0drv.blog.social.service.impl;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zer0drv.blog.article.api.ArticleFeedEntry;
import com.zer0drv.blog.social.domain.Notification;
import com.zer0drv.blog.social.enums.NotificationType;
import com.zer0drv.blog.social.service.NotificationIntent;
import com.zer0drv.blog.social.service.NotificationService;
import com.zer0drv.blog.subscription.api.SubscriptionNotificationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SubscriptionNotificationAdapter implements SubscriptionNotificationPort {
    private final NotificationService notifications;
    public boolean deliver(Long userId, ArticleFeedEntry article) {
        notifications.notify(new NotificationIntent(userId, NotificationType.ARTICLE_PUBLISHED,
                article.authorId(), article.id(), null, article.title(), true));
        return notifications.count(Wrappers.lambdaQuery(Notification.class)
                .eq(Notification::getUserId, userId).eq(Notification::getArticleId, article.id())
                .eq(Notification::getType, NotificationType.ARTICLE_PUBLISHED.name())) > 0;
    }
}
