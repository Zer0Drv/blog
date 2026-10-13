package com.zer0drv.blog.friendlink.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 友情链接表（issue #17，deleted 走实体级 @TableLogic，与 SiteConfig 一致）
 *
 * @author Yoruhaki
 */
@Data
@TableName(value = "friend_link")
public class FriendLink {

    /**
     * 状态：启用
     */
    public static final short STATUS_ENABLED = 1;

    /**
     * 状态：停用
     */
    public static final short STATUS_DISABLED = 0;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 站点名称
     */
    @TableField(value = "name")
    private String name;

    /**
     * 链接地址
     */
    @TableField(value = "url")
    private String url;

    /**
     * Logo 图片 URL
     */
    @TableField(value = "logo")
    private String logo;

    /**
     * 一句话描述
     */
    @TableField(value = "description")
    private String description;

    /**
     * 排序值（升序，越小越靠前）
     */
    @TableField(value = "sort")
    private Integer sort;

    /**
     * 状态：1-启用 0-停用
     */
    @TableField(value = "status")
    private Short status;

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
    @TableLogic
    @TableField(value = "deleted")
    private Short deleted;
}
