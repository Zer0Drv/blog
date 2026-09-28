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
     * 被关注
     */
    FOLLOW,

    /**
     * 私信
     */
    PRIVATE_MESSAGE,

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
