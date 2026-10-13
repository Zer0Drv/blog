package com.zer0drv.blog.article.vo;

import java.util.List;

/**
 * 文章导入结果（issue #16）：单文件与 zip 批量导入统一响应。
 * imported 为成功创建的草稿清单（含警告与待转存外链图片清单），failed 为失败明细；
 * 单文件导入的硬性失败（扩展名 / 大小 / front matter 非法等）仍抛业务异常，
 * 不进 failed 列表；zip 导入逐条容错，失败只进 failed 不影响其他条目。
 *
 * @author Yoruhaki
 */
public record ArticleImportResultVO(List<ImportedItem> imported, List<FailedItem> failed) {

    /**
     * 成功导入的草稿
     *
     * @param id             新建草稿 id
     * @param title          草稿标题
     * @param warnings       导入警告（被忽略的字段 / 未匹配的分类等）
     * @param externalImages 正文中的外链图片清单（保留原样，未转存附件库）
     */
    public record ImportedItem(Long id, String title, List<String> warnings, List<String> externalImages) {
    }

    /**
     * 导入失败明细（zip 批量场景）
     *
     * @param name    文件名 / zip 条目名
     * @param message 失败原因
     */
    public record FailedItem(String name, String message) {
    }
}
