package com.zer0drv.blog.social.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 关注关系表（无逻辑删除字段，取关走物理删除）
 *
 * @author Yoruhaki
 */
@Data
@TableName(value = "follow")
public class Follow {

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 关注人id
     */
    @TableField(value = "follower_id")
    private Long followerId;

    /**
     * 被关注人id
     */
    @TableField(value = "followee_id")
    private Long followeeId;

    /**
     * 创建时间
     */
    @TableField(value = "create_time")
    private LocalDateTime createTime;
}
