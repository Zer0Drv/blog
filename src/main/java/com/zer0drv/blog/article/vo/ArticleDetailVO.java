package com.zer0drv.blog.article.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文章详情 = 列表项 + 正文与编辑器类型
 *
 * @author Yoruhaki
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ArticleDetailVO extends ArticleListVO {

    private String content;

    private String editorType;

    /**
     * 浏览量（M3）
     */
    private Long viewCount;

    /**
     * 点赞数（实时 count article_like）
     */
    private Long likeCount;

    /**
     * 收藏数（实时 count article_favorite）
     */
    private Long favoriteCount;

    /**
     * 评论数（实时 count NORMAL 评论）
     */
    private Long commentCount;

    /**
     * 当前登录人是否已赞（匿名 false）
     */
    private Boolean liked;

    /**
     * 当前登录人是否已收藏（匿名 false）
     */
    private Boolean favorited;
}