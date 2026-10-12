package com.zer0drv.blog.article.api;

/**
 * 文章评论计数读端口（issue #34 第三步）：comment 表按文章维度的可见计数，
 * 由拥有 comment 表的 comment 模块实现。
 *
 * <p>与 {@link ArticleCascade} / {@link ArticleNotificationPort} 同款模式：
 * article.api 声明窄契约、零 MyBatis-Plus 类型、拥有数据的模块实现——
 * 文章域装配详情页评论计数（fillInteraction）不再直注外模块 Mapper。
 *
 * <p>同步执行于调用方上下文中；只读，无事务要求。
 */
public interface ArticleCommentStatsPort {

    /**
     * 文章对外可见评论总数。计数口径与收口前 fillInteraction 一致：
     * 仅 {@code status=NORMAL} 的评论（FOLDED / PENDING 不计，逻辑删除由 MP 全局过滤）。
     */
    long countVisibleByArticleId(long articleId);
}
