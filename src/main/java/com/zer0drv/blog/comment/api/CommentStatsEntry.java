package com.zer0drv.blog.comment.api;

import java.time.LocalDateTime;

/**
 * 管理端统计投影：最新评论条目（{@link CommentStatsPort#listLatest} 的返回载体）。
 *
 * <p>不过滤评论状态（管理端审核视角，NORMAL / FOLDED / PENDING 均透出），
 * 只暴露统计列表需要的最小字段；content 为原文，截断由调用方处理。
 *
 * @param id         评论 id
 * @param articleId  所属文章 id（文章标题联查随行）
 * @param userId     评论作者 id（用户名联查随行）
 * @param content    评论内容原文
 * @param createTime 评论时间
 */
public record CommentStatsEntry(long id, long articleId, long userId, String content,
                                LocalDateTime createTime) {
}
