package com.zer0drv.blog.subscription.api;
import com.zer0drv.blog.article.api.ArticleFeedEntry;
public interface SubscriptionNotificationPort {
    boolean deliver(Long userId, ArticleFeedEntry article);
}
