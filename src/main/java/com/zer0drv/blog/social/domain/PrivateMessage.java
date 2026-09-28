package com.zer0drv.blog.social.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 私信表（v1 轮询；deleted 走 MyBatis-Plus 全局逻辑删除）
 *
 * @author Yoruhaki
 */
@Data
@TableName(value = "private_message")
public class PrivateMessage {

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 发送人id
     */
    @TableField(value = "sender_id")
    private Long senderId;

    /**
     * 接收人id
     */
    @TableField(value = "receiver_id")
    private Long receiverId;

    /**
     * 消息内容
     */
    @TableField(value = "content")
    private String content;

    /**
     * 已读：0-未读；1-已读
     */
    @TableField(value = "read_flag")
    private Short readFlag;

    /**
     * 创建时间
     */
    @TableField(value = "create_time")
    private LocalDateTime createTime;

    /**
     * 逻辑删除：0-未删除；1-删除
     */
    @TableField(value = "deleted")
    private Short deleted;
}
