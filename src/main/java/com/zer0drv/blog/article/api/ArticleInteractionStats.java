package com.zer0drv.blog.article.api;

/**
 * 文章互动计数与访问者状态的只读投影（{@link ArticleInteractionPort#statsOf} 的返回载体）。
 *
 * @param likeCount     点赞总数（实时 count article_like）
 * @param favoriteCount 收藏总数（实时 count article_favorite）
 * @param liked         当前访问者是否已赞（匿名恒 false）
 * @param favorited     当前访问者是否已收藏（匿名恒 false）
 */
public record ArticleInteractionStats(long likeCount, long favoriteCount, boolean liked, boolean favorited) {
}
