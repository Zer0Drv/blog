package com.zer0drv.blog.article.api;

/**
 * 文章互动数据读端口（issue #34 第三步）：article_like / article_favorite 的
 * 计数与「当前访问者是否已赞 / 已藏」查询，由拥有这两张表的 interaction 模块实现。
 *
 * <p>与 {@link ArticleCascade} / {@link ArticleNotificationPort} 同款模式：
 * article.api 声明窄契约、零 MyBatis-Plus 类型、拥有数据的模块实现——
 * 文章域装配详情页互动数据（fillInteraction）不再直注外模块 Mapper。
 *
 * <p>同步执行于调用方上下文中；只读，无事务要求。
 */
public interface ArticleInteractionPort {

    /**
     * 查文章的点赞 / 收藏计数与访问者状态。
     *
     * @param articleId 文章 id
     * @param viewerId  当前访问者 id；匿名传 null，此时 liked / favorited 恒 false
     *                  （不触发用户维度查询），语义与收口前 fillInteraction 逐字节等价
     */
    ArticleInteractionStats statsOf(long articleId, Long viewerId);
}
