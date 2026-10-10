package com.zer0drv.blog.common.sensitive;

/**
 * 敏感词命中判定（M5）。comment 等下游模块只依赖本接口，
 * 词库 CRUD 管理面仍由 admin 模块的 SensitiveWordService 提供（extends 本接口），
 * 以此解除 comment → admin 的包级依赖（原循环：admin 审核补发通知依赖 comment）。
 *
 * @author Yoruhaki
 */
public interface SensitiveWordChecker {

    /**
     * 内容是否命中任一敏感词（忽略大小写包含；blank 内容直接 false）
     */
    boolean containsSensitiveWord(String content);
}
