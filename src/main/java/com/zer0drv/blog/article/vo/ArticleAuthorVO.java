package com.zer0drv.blog.article.vo;

import lombok.Data;

/**
 * 文章作者简要信息
 *
 * @author Yoruhaki
 */
@Data
public class ArticleAuthorVO {

    private Long id;

    private String username;

    private String nickname;

    private String avatar;
}