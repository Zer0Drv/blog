package com.zer0drv.blog.subscription.service;
import com.zer0drv.blog.subscription.repository.SubscriptionRepository;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zer0drv.blog.article.api.ArticleCatalog;
import com.zer0drv.blog.article.api.ArticleFeedEntry;
import com.zer0drv.blog.article.api.ArticlePublicationCandidate;
import com.zer0drv.blog.site.service.SiteConfigService;
import com.zer0drv.blog.subscription.api.SubscriptionNotificationPort;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
@RequiredArgsConstructor
public class SubscriptionWorker {
    private final SubscriptionRepository repository;
    private final UserService users;
    private final ArticleCatalog articles;
    private final SubscriptionNotificationPort notifications;
    private final SiteConfigService site;
    private final ObjectProvider<JavaMailSender> mail;
    private final AtomicBoolean running = new AtomicBoolean();
    @Value("${blog.subscription.worker-enabled:true}") private boolean enabled;
    @Value("${spring.mail.username:}") private String from;

    /** 与文章保存同事务写入持久队列，发布成功即保证任务存在。 */
    @EventListener
    public void enqueue(ArticlePublicationCandidate event) { repository.enqueue(event); }

    @Async
    @Scheduled(fixedDelayString = "${blog.subscription.poll-ms:60000}", initialDelayString = "${blog.subscription.poll-ms:60000}")
    public void tick() {
        if (!enabled || !running.compareAndSet(false, true)) return;
        try { processPending(); } catch (Exception e) { log.warn("订阅任务失败，下次重试", e); }
        finally { running.set(false); }
    }

    public void processPending() {
        for (var publication : repository.pending(LocalDateTime.now())) {
            String token = java.util.UUID.randomUUID().toString().replace("-", "");
            if (!repository.claim(publication.id(), LocalDateTime.now(), token)) continue;
            boolean retry = false;
            var entry = articles.findVisibleFeedEntry(publication.id());
            if (entry.isEmpty()) { repository.finish(publication.id(), token, "HIDDEN"); continue; }
            long cursor = 0;
            while (true) {
                if (!repository.heartbeat(publication.id(), token)) break;
                var recipients = users.list(Wrappers.lambdaQuery(User.class).eq(User::getArticleNotifyEnabled, 1)
                        .eq(User::getStatus, 0).le(User::getArticleSubscribedAt, publication.dueAt())
                        .gt(User::getId, cursor).orderByAsc(User::getId).last("LIMIT 100"));
                if (recipients.isEmpty()) break;
                for (User recipient : recipients) {
                    if (!repository.heartbeat(publication.id(), token)) return;
                    cursor = recipient.getId();
                    if (recipient.getId().equals(entry.get().authorId())) continue;
                    var delivery = repository.delivery(publication.id(), recipient.getId());
                    if (delivery.attempts() >= 3 || ("DONE".equals(delivery.state()) && !"FAILED".equals(delivery.mailState()))) continue;
                    // 每个收件人发送前重新检查，任务期间文章改私密/下架即停止。
                    var current = articles.findVisibleFeedEntry(publication.id());
                    if (current.isEmpty()) { retry = true; break; }
                    User fresh = users.getById(recipient.getId());
                    if (fresh == null || !SubscriptionService.enabled(fresh) || Short.valueOf((short) 1).equals(fresh.getStatus())) continue;
                    String state = "FAILED", mailState = delivery.mailState();
                    try {
                        if ("DONE".equals(delivery.state()) || notifications.deliver(fresh.getId(), current.get())) {
                            state = "DONE";
                            if (!"SENT".equals(mailState) && !"DEV".equals(mailState)) mailState = sendMail(fresh, current.get());
                        }
                    } catch (Exception e) { mailState = "FAILED"; log.warn("订阅投递失败 articleId={} userId={}", publication.id(), fresh.getId(), e); }
                    repository.record(publication.id(), fresh.getId(), state, mailState);
                    if (("FAILED".equals(state) || "FAILED".equals(mailState)) && delivery.attempts() < 2) retry = true;
                }
                if (recipients.size() < 100) break;
            }
            repository.finish(publication.id(), token, retry ? "PENDING" : "DONE");
        }
    }

    private String sendMail(User user, ArticleFeedEntry article) {
        if (!Short.valueOf((short) 1).equals(user.getArticleEmailNotifyEnabled()) || user.getEmail() == null || user.getEmail().isBlank() || user.getEmail().endsWith("@oauth.local")) return "SKIPPED";
        if (!repository.reserveEmail(user.getId())) return "LIMITED";
        JavaMailSender sender = mail.getIfAvailable();
        if (sender == null) { log.info("【dev-email-fallback】订阅邮件 userId={} articleId={}", user.getId(), article.id()); return "DEV"; }
        String base = site.getValue("site.base_url", "http://localhost:5173").replaceAll("/+$", "");
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(user.getEmail());
        if (from != null && !from.isBlank()) message.setFrom(from);
        message.setSubject("【" + site.getValue("site.name", "Blog") + "】新文章发布");
        message.setText(article.title() + "\n\n阅读：" + base + "/article/" + (article.slug() == null ? article.id() : article.slug())
                + "\n\n退订：" + base + "/subscription/unsubscribe?token=" + user.getArticleUnsubscribeToken());
        sender.send(message);
        return "SENT";
    }
}
