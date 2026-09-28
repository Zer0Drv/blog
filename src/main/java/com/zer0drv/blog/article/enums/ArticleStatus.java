package com.zer0drv.blog.article.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 文章状态：DRAFT=草稿；PUBLISHED=已发布；OFFLINE=已下架。
 *
 * @author Yoruhaki
 */
@Getter
@RequiredArgsConstructor
public enum ArticleStatus {
    DRAFT("草稿"),
    PUBLISHED("已发布"),
    OFFLINE("已下架");

    private final String desc;

    public static ArticleStatus of(String code) {
        for (ArticleStatus value : ArticleStatus.values()) {
            if (value.name().equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Invalid article status code: " + code);
    }

    public static boolean isValid(String code) {
        for (ArticleStatus value : ArticleStatus.values()) {
            if (value.name().equals(code)) {
                return true;
            }
        }
        return false;
    }
}