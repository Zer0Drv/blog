package com.zer0drv.blog.article.api;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 文章可见性级别（issue #15）：PUBLIC=公开；PASSWORD=口令保护；PRIVATE=私密（仅作者/管理员）。
 *
 * <p>与 {@link ArticleStatus} 正交：status 管生命周期（草稿/发布/下架 + deleted 回收站），
 * 本枚举只在「已发布且到点」之后决定受众范围。公众可见性谓词（列表/Feed/归档/搜索/
 * RSS/sitemap 共用）要求 {@code visibility = PUBLIC}，单一出处见
 * {@code article/domain/ArticleVisibility}。
 *
 * <p>落 {@code article.api} 契约包：跨模块契约枚举的法定位置（ArchUnit 红线注释，
 * 本包零 MyBatis-Plus 类型约束不变）。
 *
 * @author Yoruhaki
 */
@Getter
@RequiredArgsConstructor
public enum Visibility {
    PUBLIC("公开"),
    PASSWORD("口令保护"),
    PRIVATE("私密");

    private final String desc;

    public static Visibility of(String code) {
        for (Visibility value : Visibility.values()) {
            if (value.name().equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Invalid article visibility code: " + code);
    }

    public static boolean isValid(String code) {
        for (Visibility value : Visibility.values()) {
            if (value.name().equals(code)) {
                return true;
            }
        }
        return false;
    }
}
