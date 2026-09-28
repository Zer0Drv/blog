package com.zer0drv.blog.article.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 编辑器类型：MARKDOWN=Markdown；RICHTEXT=富文本。两种模式内容不互通，按文章的 editorType 锁定。
 *
 * @author Yoruhaki
 */
@Getter
@RequiredArgsConstructor
public enum EditorType {
    MARKDOWN("Markdown"),
    RICHTEXT("富文本");

    private final String desc;

    public static boolean isValid(String code) {
        for (EditorType value : EditorType.values()) {
            if (value.name().equals(code)) {
                return true;
            }
        }
        return false;
    }
}