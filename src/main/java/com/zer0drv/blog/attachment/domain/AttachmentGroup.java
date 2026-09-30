package com.zer0drv.blog.attachment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 附件分组表
 *
 * @author Yoruhaki
 */
@Data
@TableName(value = "attachment_group")
public class AttachmentGroup {

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 属主用户id
     */
    @TableField(value = "user_id")
    private Long userId;

    /**
     * 分组名
     */
    @TableField(value = "name")
    private String name;

    /**
     * 排序值，越小越靠前
     */
    @TableField(value = "sort")
    private Integer sort;

    /**
     * 创建时间
     */
    @TableField(value = "create_time")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField(value = "update_time")
    private LocalDateTime updateTime;

    /**
     * 逻辑删除：0-未删除；1-删除
     */
    @TableField(value = "deleted")
    private Short deleted;
}
