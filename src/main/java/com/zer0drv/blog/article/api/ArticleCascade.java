package com.zer0drv.blog.article.api;

/**
 * 文章物理删除的级联清场端口：由拥有相关表的模块各自实现，文章域统一编排。同步、同事务执行。
 *
 * <p>issue #24 第三步：{@code forceDelete} 不再直注外模块 Mapper 编排跨模块删除，
 * 而是注入 {@code List<ArticleCascade>} 遍历清场。每个实现只删自己拥有的表：
 * <ul>
 *   <li>comment 模块：comment（含已逻辑删除）+ comment_like；</li>
 *   <li>interaction 模块：article_like + article_favorite。</li>
 * </ul>
 *
 * <p>article 自有数据（article 本行、article_tag、article_version、autosave Redis 键）
 * 不属于本端口职责，仍由文章域自理。
 *
 * <p>调用方以 {@code @Transactional} 包住全部 {@link #purgeByArticleId} 调用，
 * 任一实现抛异常即整体回滚——实现不得自行捕获吞掉异常，也不得另起事务。
 */
public interface ArticleCascade {

    /**
     * 物理清除本模块拥有的、与指定文章关联的全部数据。
     * 同步执行于调用方事务内；文章不存在时按空集处理（幂等，不抛异常）。
     */
    void purgeByArticleId(long articleId);
}
