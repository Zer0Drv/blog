package com.zer0drv.blog.article.api;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 文章状态：DRAFT=草稿；PUBLISHED=已发布；OFFLINE=已下架。
 *
 * <p>issue #34 第六步自 {@code article.enums} 上移本包：admin 等外模块
 * 需要引用状态枚举做参数校验与状态写操作，跨模块契约类型一律归属
 * {@code article.api}（本包零 MyBatis-Plus 类型约束不变）。
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