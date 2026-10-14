package com.zer0drv.blog.article.api;
import java.time.LocalDateTime;
/** 保存公开发布文章时的事务内事件；定时文章按 dueAt 排队。 */
public record ArticlePublicationCandidate(Long articleId, LocalDateTime dueAt) {}
