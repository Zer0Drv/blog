package com.zer0drv.blog.article.api;

import java.time.LocalDateTime;

/**
 * 管理端统计投影：最新发布文章条目（{@link ArticleStatsPort#listPublishedLatest} 的返回载体）。
 *
 * <p>口径为管理端语义（仅 {@code status=PUBLISHED}，无 publish_time 条件），
 * 与 {@link ArticleCatalog} 的可见性谓词刻意分离；publishTime 可为 null
 * （PUBLISHED 但未到点 / 无定时记录的同行原样透出，由调用方展示层处理）。
 */
public record ArticlePublishedEntry(Long id, Long authorId, String title, LocalDateTime publishTime) {
}
