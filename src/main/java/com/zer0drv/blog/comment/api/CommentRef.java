package com.zer0drv.blog.comment.api;

/**
 * 可点赞评论的引用投影（{@link CommentLikePort#findLikeable} 的返回载体）。
 *
 * <p>只暴露点赞流程需要的最小字段；评论状态不出现在投影里——「仅 NORMAL 可点赞」
 * 的判定由 comment 模块在查询侧完成，调用方拿到的即可赞。
 *
 * @param id        评论 id
 * @param authorId  评论作者 id（点赞通知接收人）
 * @param articleId 所属文章 id（点赞通知随行）
 * @param content   评论内容原文（调用方按需截取通知摘要；可为 null）
 */
public record CommentRef(long id, long authorId, long articleId, String content) {
}
