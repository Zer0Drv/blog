package com.zer0drv.blog.social.service;

import com.zer0drv.blog.social.enums.NotificationType;

/**
 * 评论邮件通知（P0 §2.2）：异步发送，任何失败仅记日志、不影响主业务。
 * 跳过条件：用户不存在 / 用户关闭邮件通知 / 邮箱为空或 @oauth.local 占位邮箱 / 未配置 SMTP。
 *
 * @author Yoruhaki
 */
public interface NotificationMailService {

    /**
     * 异步发送评论相关邮件通知（COMMENT_REPLY/MENTION 场景）
     *
     * @param targetUserId  接收人 id
     * @param type          通知类型
     * @param actorNickname 触发人昵称（可空，空时正文用兜底文案）
     * @param articleId     文章 id（查标题 + 拼跳转链接）
     * @param summary       评论摘要
     */
    void sendCommentMailAsync(Long targetUserId, NotificationType type, String actorNickname,
                              Long articleId, String summary);
}
