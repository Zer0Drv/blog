package com.zer0drv.blog.article.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文章版本详情 = 列表项 + 正文等完整快照字段
 *
 * @author Yoruhaki
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ArticleVersionDetailVO extends ArticleVersionVO {

    private String summary;

    private String content;

    private String editorType;

    private String cover;

    private Long categoryId;
}
