package com.zer0drv.blog.social.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 私信会话列表项（按 lastMessage.createTime 倒序）
 *
 * @author Yoruhaki
 */
@Data
public class ConversationVO {

    /**
     * 会话对方
     */
    private SocialUserVO peer;

    /**
     * 该会话最新一条消息
     */
    private LastMessage lastMessage;

    /**
     * 该会话中对方发给我的未读消息数
     */
    private Long unreadCount;

    /**
     * 会话最新一条消息摘要
     */
    @Data
    public static class LastMessage {

        private String content;

        private LocalDateTime createTime;

        private Long senderId;
    }
}
