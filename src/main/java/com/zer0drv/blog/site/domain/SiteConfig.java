package com.zer0drv.blog.site.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站点配置表
 *
 * @author Yoruhaki
 */
@Data
@TableName(value = "site_config")
public class SiteConfig {

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 配置键
     */
    @TableField(value = "config_key")
    private String configKey;

    /**
     * 配置值
     */
    @TableField(value = "config_value")
    private String configValue;

    /**
     * 说明
     */
    @TableField(value = "description")
    private String description;

    /**
     * 更新时间
     */
    @TableField(value = "update_time")
    private LocalDateTime updateTime;

    /**
     * 逻辑删除：0-未删除；1-删除
     * （本工程 MP 全局 logic-delete-field 配置不生效，须实体级 @TableLogic，与 Article/Comment 一致）
     */
    @com.baomidou.mybatisplus.annotation.TableLogic
    @TableField(value = "deleted")
    private Short deleted;
}
