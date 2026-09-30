package com.zer0drv.blog.article.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zer0drv.blog.article.vo.ArticleDetailVO;
import com.zer0drv.blog.article.vo.ArticleListVO;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文章表
 *
 * @author Yoruhaki
 */
@Data
@TableName(value = "article")
@AutoMapper(target = ArticleListVO.class, reverseConvertGenerate = false)
@AutoMapper(target = ArticleDetailVO.class, reverseConvertGenerate = false)
public class Article {

    /**
     * 文章id
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

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
     * 正文纯文本（V7，全文搜索用；保存时由 content 清洗生成）
     */
    @TableField(value = "content_text")
    private String contentText;

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
     * 作者id
     */
    @TableField(value = "author_id")
    private Long authorId;

    /**
     * 状态：DRAFT / PUBLISHED / OFFLINE
     */
    @TableField(value = "status")
    private String status;

    /**
     * 首次发布时间
     */
    @TableField(value = "publish_time")
    private LocalDateTime publishTime;

    /**
     * 浏览量（M3，仅 PUBLISHED 详情访问时原子自增）
     */
    @TableField(value = "view_count")
    private Long viewCount;

    /**
     * 置顶：0-否；1-是（M5，首页 is_top DESC 优先展示）
     */
    @TableField(value = "is_top")
    private Short isTop;

    /**
     * 推荐位：0-否；1-是（M5）
     */
    @TableField(value = "is_recommended")
    private Short isRecommended;

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
     * 逻辑删除：0-未删除；1-删除（显式 @TableLogic：全局 logic-delete-field 配置在本工程未生效，
     * 实测 removeById 走了物理删除，回收站依赖实体级注解保证逻辑删）
     */
    @TableLogic
    @TableField(value = "deleted")
    private Short deleted;
}