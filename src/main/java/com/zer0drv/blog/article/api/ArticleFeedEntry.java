package com.zer0drv.blog.article.api;

import java.time.LocalDateTime;

/**
 * 订阅源 / sitemap 专用投影（SEO 冷路径）。
 * authorId 供订阅源按作者联查昵称（entry author）；
 * slug 供生成固定链接（issue #14），可为 null（异常存量），消费方回退 id 链接。
 *
 * @author Yoruhaki
 */
public record ArticleFeedEntry(Long id, Long authorId, String title, String summary, String slug,
                               LocalDateTime publishTime, LocalDateTime updateTime) {
}
