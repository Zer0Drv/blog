package com.zer0drv.blog.article.api;

/**
 * 文章引用投影：跨模块传递的最小字段集（id / 作者 / 标题）。
 * 评论与审核通知分发、邮件通知标题展示、管理端评论列表标题联查用。
 * 字段上限 5 个，超出走列表 / 分页方法。
 *
 * @author Yoruhaki
 */
public record ArticleRef(Long id, Long authorId, String title) {
}
