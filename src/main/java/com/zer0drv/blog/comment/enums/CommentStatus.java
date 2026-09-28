package com.zer0drv.blog.comment.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 评论状态：NORMAL=正常；FOLDED=折叠（M5 审核用，本阶段只写 NORMAL）。
 *
 * @author Yoruhaki
 */
@Getter
@RequiredArgsConstructor
public enum CommentStatus {
    NORMAL("正常"),
    FOLDED("折叠");

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