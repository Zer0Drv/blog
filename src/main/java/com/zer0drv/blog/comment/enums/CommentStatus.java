package com.zer0drv.blog.comment.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 评论状态：NORMAL=正常；FOLDED=折叠（敏感词命中/审核拒绝）；PENDING=待审核（P0 审核队列，公开列表不可见）。
 *
 * @author Yoruhaki
 */
@Getter
@RequiredArgsConstructor
public enum CommentStatus {
    NORMAL("正常"),
    FOLDED("折叠"),
    PENDING("待审核");

    private final String desc;

    public static boolean isValid(String code) {
        for (CommentStatus value : CommentStatus.values()) {
            if (value.name().equals(code)) {
                return true;
            }
        }
        return false;
    }
}