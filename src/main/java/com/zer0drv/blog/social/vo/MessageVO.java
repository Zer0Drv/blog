package com.zer0drv.blog.social.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 私信消息项（createTime 倒序返回，前端倒转展示）
 *
 * @author Yoruhaki
 */
@Data
public class MessageVO {

    private Long id;

    private Long senderId;

    private Long receiverId;

    private String content;

    /**
     * 已读：0-未读；1-已读
     */
    private Short readFlag;

    private LocalDateTime createTime;
}
