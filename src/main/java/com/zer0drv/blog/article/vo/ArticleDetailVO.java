package com.zer0drv.blog.article.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文章详情 = 列表项 + 正文与编辑器类型
 *
 * @author Yoruhaki
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ArticleDetailVO extends ArticleListVO {

    private String content;

    private String editorType;
}