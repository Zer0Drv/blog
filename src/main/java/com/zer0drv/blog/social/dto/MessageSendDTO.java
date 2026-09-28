package com.zer0drv.blog.social.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 发送私信入参（内容长度在 service 层统一校验，抛 MESSAGE_CONTENT_INVALID）
 *
 * @author Yoruhaki
 */
@Data
public class MessageSendDTO {

    /**
     * 接收人id
     */
    @NotNull(message = "receiverId 不能为空")
    private Long receiverId;

    /**
     * 消息内容（≤1000）
     */
    private String content;
}
