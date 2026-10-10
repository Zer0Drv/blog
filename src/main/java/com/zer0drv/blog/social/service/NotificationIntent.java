package com.zer0drv.blog.social.service;

import com.zer0drv.blog.social.enums.NotificationType;

import java.util.Objects;

/**
 * 一次通知投递意图：把通知的接收人、类型、上下文与去重策略收敛为不可变值对象，
 * 替代原先 7 个位置参数（含裸 boolean）的 notify 签名。
 *
 * <p>dedupe 策略内建于静态工厂（点赞 / 关注类幂等防重，回复 / @ / 私信每次必发），
 * 调用方不再感知该开关；各工厂与真实调用点一一对应。
 *
 * @author Yoruhaki
 */
public record NotificationIntent(Long recipientId, NotificationType type, Long actorId,
                                 Long articleId, Long commentId, String summary, boolean dedupe) {

    /**
     * 接收人与类型必填；summary 归一化为非 null（落库语义：空串而非 null）
     */
    public NotificationIntent {
        Objects.requireNonNull(recipientId, "recipientId");
        Objects.requireNonNull(type, "type");
        summary = Objects.isNull(summary) ? "" : summary;
    }

    /**
     * 评论 / 回复通知（主评论 → 文章作者；回复 → root 评论作者）
     */
    public static NotificationIntent commentReply(Long to, Long actor, Long article, Long comment, String summary) {
        return new NotificationIntent(to, NotificationType.COMMENT_REPLY, actor, article, comment, summary, false);
    }

    /**
     * @ 提及通知（回复指定了被回复人）
     */
    public static NotificationIntent mention(Long to, Long actor, Long article, Long comment, String summary) {
        return new NotificationIntent(to, NotificationType.MENTION, actor, article, comment, summary, false);
    }

    /**
     * 文章被点赞通知（summary 为文章标题；dedupe：取消再赞不重复打扰）
     */
    public static NotificationIntent articleLike(Long to, Long actor, Long article, String title) {
        return new NotificationIntent(to, NotificationType.ARTICLE_LIKE, actor, article, null, title, true);
    }

    /**
     * 评论被点赞通知（summary 为评论内容摘要；dedupe：取消再赞不重复打扰）
     */
    public static NotificationIntent commentLike(Long to, Long actor, Long article, Long comment, String summary) {
        return new NotificationIntent(to, NotificationType.COMMENT_LIKE, actor, article, comment, summary, true);
    }

    /**
     * 被关注通知（dedupe：取关再关注不重复打扰）
     */
    public static NotificationIntent follow(Long to, Long actor) {
        return new NotificationIntent(to, NotificationType.FOLLOW, actor, null, null, "", true);
    }

    /**
     * 私信通知（summary 为内容前 50 字）
     */
    public static NotificationIntent privateMessage(Long to, Long actor, String summary) {
        return new NotificationIntent(to, NotificationType.PRIVATE_MESSAGE, actor, null, null, summary, false);
    }
}
