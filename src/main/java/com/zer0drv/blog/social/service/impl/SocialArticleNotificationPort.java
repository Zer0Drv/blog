package com.zer0drv.blog.social.service.impl;

import com.zer0drv.blog.article.api.ArticleNotificationPort;
import com.zer0drv.blog.social.service.NotificationIntent;
import com.zer0drv.blog.social.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * social 模块的 {@link ArticleNotificationPort} 实现（参照 InteractionArticleCascade
 * 同款适配器模式）：组装 {@link NotificationIntent} 后委托 {@link NotificationService#notify}，
 * 落库 / WS / 邮件三通道的隔离兜底由 notify 内部保证——通知失败不炸删除主流程。
 *
 * <p>{@link NotificationService} 以 {@link ObjectProvider} 懒解析：其实现经
 * NotificationMailServiceImpl → ArticleCatalog → ArticleService 的链路回到文章域，
 * 构造器直注会与 ArticleServiceImpl → 本端口 形成循环依赖（容器启动即失败）。
 *
 * @author Yoruhaki
 */
@Component
@RequiredArgsConstructor
public class SocialArticleNotificationPort implements ArticleNotificationPort {

    private final ObjectProvider<NotificationService> notificationServiceProvider;

    @Override
    public void notifyAuthorForceDeleted(Long authorId, Long operatorId, String articleTitle) {
        notificationServiceProvider.getObject()
                .notify(NotificationIntent.articleForceDeleted(authorId, operatorId, articleTitle));
    }
}
