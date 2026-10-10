package com.zer0drv.blog.article.api;

import java.time.LocalDateTime;

/**
 * 订阅源 / sitemap 专用投影（SEO 冷路径）。
 * authorId 供订阅源按作者联查昵称（entry author）。
 *
 * @author Yoruhaki
 */
public record ArticleFeedEntry(Long id, Long authorId, String title, String summary,
                               LocalDateTime publishTime, LocalDateTime updateTime) {
}
