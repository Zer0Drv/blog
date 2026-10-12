package com.zer0drv.blog.social.enums;

import java.util.Objects;

/**
 * 通知类型
 *
 * @author Yoruhaki
 */
public enum NotificationType {

    /**
     * 评论 / 回复
     */
    COMMENT_REPLY,

    /**
     * @ 提及
     */
    MENTION,

    /**
     * 文章被点赞
     */
    ARTICLE_LIKE,

    /**
     * 评论被点赞
     */
    COMMENT_LIKE,

    /**
     * 被关注
     */
    FOLLOW,

    /**
     * 私信
     */
    PRIVATE_MESSAGE,

    /**
     * 文章被管理员彻底删除（违规强删）：operator ≠ author 时通知作者，
     * summary 为被删文章标题，articleId 为空（行已物理删除，不可再跳转）
     */
    ARTICLE_FORCE_DELETED,

    /**
     * 系统通知
     */
    SYSTEM;

    /**
     * 是否为合法类型名（用于通知列表的 type 过滤入参校验）
     */
    public static boolean isValid(String type) {
        if (Objects.isNull(type) || type.isBlank()) {
            return false;
        }
        for (NotificationType value : values()) {
            if (value.name().equals(type)) {
                return true;
            }
        }
        return false;
    }
}
