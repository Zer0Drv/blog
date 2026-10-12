package com.zer0drv.blog.article.api;

/**
 * 文章-标签关联维护端口（issue #34 第四步）：article_tag 关联行的写维护，
 * 由拥有关联表的 article 模块实现。
 *
 * <p>与 {@link ArticleCatalog}（只读目录）刻意分离：关联清理是写操作，
 * 独立小接口避免把写入口混进只读契约。与 {@link ArticleCascade} /
 * {@link ArticleNotificationPort} 同款模式：窄契约、零 MyBatis-Plus 类型，
 * 方法同步执行于调用方事务内。
 */
public interface ArticleTagMaintenance {

    /**
     * 物理删除某标签的全部文章关联行（article_tag 无逻辑删除字段）。
     * 标签删除流程在删标签行后调用；无关联行时为空操作。
     */
    void unlinkTag(long tagId);
}
