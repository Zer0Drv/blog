package com.zer0drv.blog.article.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文章详情 = 列表项 + 正文（editorType 自 ArticleListVO 继承）
 *
 * @author Yoruhaki
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ArticleDetailVO extends ArticleListVO {

    private String content;

    /**
     * 是否需要先校验访问口令（issue #15）：true 表示这是一篇已到点的 PASSWORD 文章且
     * 当前请求未持有效访问令牌——此时本 VO 仅回填 id / slug / visibility / 本标记，
     * 标题 / 摘要 / 正文等一律不下发（避免口令页泄露内容）；正常详情恒为 false
     */
    private Boolean passwordRequired;

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
