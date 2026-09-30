package com.zer0drv.blog.article.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文章版本列表项（不含正文）
 *
 * @author Yoruhaki
 */
@Data
public class ArticleVersionVO {

    private Long id;

    private Integer version;

    private String title;

    private LocalDateTime createTime;
}
