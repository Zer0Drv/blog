package com.zer0drv.blog.comment.service;

import com.zer0drv.blog.comment.enums.CommentStatus;

/**
 * 评论创建结论：id + 落库状态随行返回，调用方（controller）无需重新推导
 * 敏感词/审核开关判定（原实现为拼响应文案把同一判定跑了第二遍）。
 *
 * @author Yoruhaki
 */
public record CommentCreateResult(Long id, CommentStatus status) {
}
