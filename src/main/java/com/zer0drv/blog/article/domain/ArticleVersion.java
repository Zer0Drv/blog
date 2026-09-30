package com.zer0drv.blog.article.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文章版本快照表（P0）：update / 恢复版本时对「更新前的旧行」留快照，
 * create 与 updateStatus 不产快照；标签不纳入版本（SPEC 决策）。
 *
 * @author Yoruhaki
 */
@Data
@TableName(value = "article_version")
public class ArticleVersion {

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
     * 版本号（自 1 递增，按文章独立编号）
     */
    @TableField(value = "version")
    private Integer version;

    /**
     * 标题
     */
    @TableField(value = "title")
    private String title;

    /**
     * 摘要
     */
    @TableField(value = "summary")
    private String summary;

    /**
     * 正文（Markdown 或富文本 HTML）
     */
    @TableField(value = "content")
    private String content;

    /**
     * 编辑器类型：MARKDOWN / RICHTEXT
     */
    @TableField(value = "editor_type")
    private String editorType;

    /**
     * 封面图 URL
     */
    @TableField(value = "cover")
    private String cover;

    /**
     * 分类id
     */
    @TableField(value = "category_id")
    private Long categoryId;

    /**
     * 快照时间
     */
    @TableField(value = "create_time")
    private LocalDateTime createTime;

    /**
     * 逻辑删除：0-未删除；1-删除
     */
    @TableLogic
    @TableField(value = "deleted")
    private Short deleted;
}
