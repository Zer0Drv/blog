package com.zer0drv.blog.article.vo;

/**
 * 文章导出结果：文件字节 + 文件名（不含扩展名）+ 扩展名（md / html）。
 * 由 Controller 组装 Content-Type 与 Content-Disposition，不落临时文件。
 *
 * @author Yoruhaki
 */
public record ArticleExportVO(byte[] body, String fileName, String extension) {
}
