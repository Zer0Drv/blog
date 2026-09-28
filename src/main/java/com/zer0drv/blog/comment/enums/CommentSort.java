package com.zer0drv.blog.comment.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 主评论排序：TIME_DESC=最新（默认）；TIME_ASC=最早；HOT=最热（like_count DESC, id DESC）。
 * 接收前端 sort 参数：time_desc / time_asc / hot，非法值回退 TIME_DESC。
 *
 * @author Yoruhaki
 */
@Getter
@RequiredArgsConstructor
public enum CommentSort {
    TIME_DESC("time_desc"),
    TIME_ASC("time_asc"),
    HOT("hot");

    /**
     * 前端传入的 sort 参数值
     */
    private final String param;

    public static CommentSort of(String param) {
        if (param != null && !param.isBlank()) {
            for (CommentSort value : CommentSort.values()) {
                if (value.param.equalsIgnoreCase(param) || value.name().equalsIgnoreCase(param)) {
                    return value;
                }
            }
        }
        return TIME_DESC;
    }
}