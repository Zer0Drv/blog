package com.zer0drv.blog.article.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 文章-标签关联表（无逻辑删除，物理删除）
 *
 * @author Yoruhaki
 */
@Data
@TableName(value = "article_tag")
public class ArticleTag {

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
     * 标签id
     */
    @TableField(value = "tag_id")
    private Long tagId;
}