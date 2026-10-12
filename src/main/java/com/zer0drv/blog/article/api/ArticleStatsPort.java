package com.zer0drv.blog.article.api;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文章统计读端口（issue #34 第五步）：管理端全站统计的文章维度口径，
 * 由拥有 article 表的 article 模块自己实现。
 *
 * <p>与 {@link ArticleCatalog} 刻意分离：Catalog 转发可见性谓词（PUBLISHED 且
 * publish_time 非空且到点），本端口承载管理端统计口径——仅 {@code status=PUBLISHED}、
 * 无 publish_time 条件，语义与收口前 AdminStatsServiceImpl 的内联查询逐字节等价。
 *
 * <p>本包零 MyBatis-Plus 类型；方法同步执行于调用方上下文中；只读，无事务要求。
 */
public interface ArticleStatsPort {

    /**
     * 已发布文章总数。管理端口径：仅 {@code status=PUBLISHED}，无 publish_time 条件
     * （逻辑删除由 MP 全局过滤）。
     */
    long countPublished();

    /**
     * create_time 不早于 since 的已发布文章数（管理端「今日新增文章」口径：
     * {@code status=PUBLISHED} 且 {@code create_time >= since}）。
     */
    long countPublishedSince(LocalDateTime since);

    /**
     * 全站文章总浏览量：{@code IFNULL(SUM(view_count), 0)}，空表 / 全 NULL 兜底 0。
     * 不过滤状态与逻辑删除以外的行（管理端全量口径，逻辑删除由 MP 全局过滤）。
     */
    long totalViews();

    /**
     * 最新已发布文章投影（publish_time 倒序，至多 limit 条）。
     * 管理端口径：仅 {@code status=PUBLISHED}，无 publish_time 非空条件。
     */
    List<ArticlePublishedEntry> listPublishedLatest(int limit);
}
