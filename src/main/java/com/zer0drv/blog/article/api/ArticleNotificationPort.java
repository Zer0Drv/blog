package com.zer0drv.blog.article.api;

/**
 * 文章域向作者发送通知的端口（产品决策：违规强删通知作者）。
 * 由拥有通知能力的模块（social）实现，文章域统一编排——与 {@link ArticleCascade}
 * 同款模式：article.api 声明窄契约、零 MyBatis-Plus 类型、外部模块实现，
 * 避免 article → social 的直接依赖成环。
 *
 * <p>同步执行于调用方事务内（{@code forceDelete} 级联清场之后）。
 * 通知是「尽力送达」：实现内部对落库 / WS / 邮件三通道独立兜底，
 * 通知失败不得抛出异常回滚删除主流程。
 *
 * <p>收藏者不通知：{@code forceDelete} 的 {@link ArticleCascade} 已物理清掉
 * article_favorite 记录，无额外通知义务。
 */
public interface ArticleNotificationPort {

    /**
     * 通知作者「文章已被管理员彻底删除」。仅在 operator ≠ author（管理员代删 /
     * 违规强删）时调用；作者自删不通知。
     *
     * @param authorId     文章作者（通知接收人）
     * @param operatorId   删除操作者（管理员）
     * @param articleTitle 被删文章标题（文章行已物理删除，标题随通知留存）
     */
    void notifyAuthorForceDeleted(Long authorId, Long operatorId, String articleTitle);
}
