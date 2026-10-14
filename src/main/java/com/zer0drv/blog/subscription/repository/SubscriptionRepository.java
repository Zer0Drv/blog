package com.zer0drv.blog.subscription.repository;
import com.zer0drv.blog.article.api.ArticlePublicationCandidate;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class SubscriptionRepository {
    private final JdbcTemplate jdbc;
    public record Publication(long id, LocalDateTime dueAt) {}
    public record Delivery(String state, String mailState, int attempts) {}

    public void enqueue(ArticlePublicationCandidate event) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        LocalDateTime dueAt = event.dueAt().isBefore(now) ? now : event.dueAt();
        jdbc.update("INSERT IGNORE INTO subscription_publication(article_id,due_at) VALUES (?,?)",
                event.articleId(), Timestamp.valueOf(dueAt));
        // 正文编辑不重置已到点的受众快照；未到点改档期与隐藏后重新公开可调整到期时间。
        jdbc.update("UPDATE subscription_publication SET due_at=?,state='PENDING' WHERE article_id=? AND ((state='PENDING' AND due_at>?) OR state='HIDDEN')",
                Timestamp.valueOf(dueAt), event.articleId(), Timestamp.valueOf(now));
    }
    public List<Publication> pending(LocalDateTime now) {
        return jdbc.query("SELECT article_id,due_at FROM subscription_publication WHERE due_at<=? AND (state='PENDING' OR (state='RUNNING' AND lease_until<?)) ORDER BY due_at,article_id LIMIT 20",
                (rs, n) -> new Publication(rs.getLong(1), rs.getTimestamp(2).toLocalDateTime()), Timestamp.valueOf(now), Timestamp.valueOf(now));
    }
    public boolean claim(long id, LocalDateTime now, String token) {
        return jdbc.update("UPDATE subscription_publication SET state='RUNNING',lease_until=?,lease_token=? WHERE article_id=? AND (state='PENDING' OR (state='RUNNING' AND lease_until<?))",
                Timestamp.valueOf(now.plusMinutes(10)), token, id, Timestamp.valueOf(now)) == 1;
    }
    public boolean heartbeat(long id, String token) {
        return jdbc.update("UPDATE subscription_publication SET lease_until=? WHERE article_id=? AND state='RUNNING' AND lease_token=?",
                Timestamp.valueOf(LocalDateTime.now().plusMinutes(10)), id, token) == 1;
    }
    public void finish(long id, String token, String state) {
        jdbc.update("UPDATE subscription_publication SET state=? WHERE article_id=? AND lease_token=?", state, id, token);
    }
    public Delivery delivery(long articleId, long userId) {
        jdbc.update("INSERT IGNORE INTO subscription_delivery(article_id,user_id) VALUES (?,?)", articleId, userId);
        return jdbc.queryForObject("SELECT state,mail_state,attempts FROM subscription_delivery WHERE article_id=? AND user_id=?",
                (rs, n) -> new Delivery(rs.getString(1), rs.getString(2), rs.getInt(3)), articleId, userId);
    }
    public void record(long articleId, long userId, String state, String mailState) {
        jdbc.update("UPDATE subscription_delivery SET state=?,mail_state=?,attempts=attempts+1,mail_sent_at=CASE WHEN ? IN ('SENT','DEV') THEN COALESCE(mail_sent_at,CURRENT_TIMESTAMP) ELSE mail_sent_at END WHERE article_id=? AND user_id=?", state, mailState, mailState, articleId, userId);
    }
    /** 跨实例原子预留发送额度；失败也占用额度，避免 SMTP 故障时反复轰炸。 */
    public boolean reserveEmail(long userId) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        Timestamp expired = Timestamp.valueOf(now.minusHours(24));
        jdbc.update("INSERT IGNORE INTO subscription_mail_budget(user_id,window_started,attempts) VALUES (?,?,0)", userId, Timestamp.valueOf(now));
        return jdbc.update("UPDATE subscription_mail_budget SET attempts=CASE WHEN window_started<=? THEN 1 ELSE attempts+1 END,window_started=CASE WHEN window_started<=? THEN ? ELSE window_started END WHERE user_id=? AND (attempts<10 OR window_started<=?)",
                expired, expired, Timestamp.valueOf(now), userId, expired) == 1;
    }
    public Map<String, Object> stats() {
        return Map.of("subscribers", jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE article_notify_enabled=1 AND deleted=0 AND status=0", Long.class),
                "pendingArticles", jdbc.queryForObject("SELECT COUNT(*) FROM subscription_publication WHERE state IN ('PENDING','RUNNING')", Long.class),
                "notifications", jdbc.queryForObject("SELECT COUNT(*) FROM subscription_delivery WHERE state='DONE'", Long.class),
                "emailsSent", jdbc.queryForObject("SELECT COUNT(*) FROM subscription_delivery WHERE mail_state='SENT'", Long.class),
                "emailsDev", jdbc.queryForObject("SELECT COUNT(*) FROM subscription_delivery WHERE mail_state='DEV'", Long.class),
                "failed", jdbc.queryForObject("SELECT COUNT(*) FROM subscription_delivery WHERE state='FAILED' OR mail_state='FAILED'", Long.class));
    }
}
