package com.zer0drv.blog.social.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知列表项（actor 为触发人简要信息，系统通知为 null）
 *
 * @author Yoruhaki
 */
@Data
public class NotificationVO {

    private Long id;

    private String type;

    private String summary;

    private Long articleId;

    private Long commentId;

    /**
     * 已读：0-未读；1-已读
     */
    private Short readFlag;

    private LocalDateTime createTime;

    private SocialUserVO actor;
}
