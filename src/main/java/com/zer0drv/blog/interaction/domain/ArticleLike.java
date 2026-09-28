package com.zer0drv.blog.interaction.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文章点赞关联表。表内虽有 deleted 列（保留位），但实体不映射该字段，
 * 因此 MyBatis-Plus 的 delete 走物理删除（取消点赞语义），查询也不附加逻辑删除条件。
 *
 * @author Yoruhaki
 */
@Data
@TableName(value = "article_like")
public class ArticleLike {

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 文章id
     */
    @TableField(value = "article_id")
    private Long articleId;

    /**
     * 用户id
     */
    @TableField(value = "user_id")
    private Long userId;

    /**
     * 创建时间
     */
    @TableField(value = "create_time")
    private LocalDateTime createTime;
}