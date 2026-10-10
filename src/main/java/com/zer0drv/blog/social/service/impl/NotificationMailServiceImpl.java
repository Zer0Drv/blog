package com.zer0drv.blog.social.service.impl;

import com.zer0drv.blog.article.api.ArticleCatalog;
import com.zer0drv.blog.article.api.ArticleRef;
import com.zer0drv.blog.site.service.SiteConfigService;
import com.zer0drv.blog.social.enums.NotificationType;
import com.zer0drv.blog.social.service.NotificationMailService;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 评论邮件通知实现（P0 §2.2）。@Async 异步发送；所有跳过/失败路径仅 log，不抛异常、不回滚主业务。
 *
 * @author Yoruhaki
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationMailServiceImpl implements NotificationMailService {

    /**
     * OAuth 注册用户占位邮箱后缀（非真实邮箱，不可投递）
     */
    private static final String OAUTH_PLACEHOLDER_SUFFIX = "@oauth.local";

    private final UserService userService;
    private final ArticleCatalog articleCatalog;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    /**
     * 站点配置（site.name / site.base_url）。实现类由 backend-C 提供，
     * 容器中没有实现类时走默认值兜底，必须 ObjectProvider 注入避免上下文启动失败
     */
    private final ObjectProvider<SiteConfigService> siteConfigServiceProvider;

    @Async
    @Override
    public void sendCommentMailAsync(Long targetUserId, NotificationType type, String actorNickname,
                                     Long articleId, String summary) {
        try {
            if (Objects.isNull(targetUserId)) {
                return;
            }
            User user = userService.getById(targetUserId);
            // 跳过：用户不存在
            if (Objects.isNull(user)) {
                return;
            }
            // 跳过：用户关闭了评论邮件通知（null 兜底视为开启，列默认 1）
            if (Objects.nonNull(user.getEmailNotifyEnabled()) && user.getEmailNotifyEnabled() == 0) {
                return;
            }
            String email = user.getEmail();
            // 跳过：邮箱为空或 OAuth 占位邮箱（不可投递）
            if (Objects.isNull(email) || email.isBlank() || email.endsWith(OAUTH_PLACEHOLDER_SUFFIX)) {
                return;
            }
            JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
            if (Objects.isNull(mailSender)) {
                // dev 兜底：未配置 SMTP 时进日志，联调不受影响；生产必须配置 spring.mail.*
                log.warn("【dev-email-fallback】type={} userId={} articleId={}（未配置 SMTP，评论通知邮件仅打印日志）",
                        type, targetUserId, articleId);
                return;
            }
            String articleTitle = Objects.nonNull(articleId)
                    ? articleCatalog.findRef(articleId).map(ArticleRef::title).orElse("") : "";
            String siteName = siteConfigValue("site.name", "Blog");
            String baseUrl = siteConfigValue("site.base_url", "http://localhost:5173");
            String actor = Objects.isNull(actorNickname) || actorNickname.isBlank() ? "有用户" : actorNickname;
            String action = type == NotificationType.MENTION ? "在评论中提到了你" : "回复了你的文章";
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(email);
            message.setSubject("【" + siteName + "】你的文章有新回复");
            message.setText(actor + " " + action
                    + (articleTitle.isBlank() ? "" : "《" + articleTitle + "》") + "：\n"
                    + (Objects.isNull(summary) ? "" : summary) + "\n\n"
                    + "查看详情：" + baseUrl + "/article/" + articleId);
            mailSender.send(message);
        } catch (Exception e) {
            // 邮件发送失败不回滚主业务
            log.warn("评论邮件通知发送失败：type={}, userId={}, articleId={}, reason={}",
                    type, targetUserId, articleId, e.getMessage());
        }
    }

    /**
     * 读站点配置；实现缺失时返回默认值（兼容 IT 与合并前环境）
     */
    private String siteConfigValue(String key, String defaultValue) {
        SiteConfigService siteConfigService = siteConfigServiceProvider.getIfAvailable();
        return Objects.nonNull(siteConfigService) ? siteConfigService.getValue(key, defaultValue) : defaultValue;
    }
}
