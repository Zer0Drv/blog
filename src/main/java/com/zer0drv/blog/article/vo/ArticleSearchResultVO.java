package com.zer0drv.blog.article.vo;

import com.zer0drv.blog.common.response.PageResult;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ArticleSearchResultVO extends PageResult<ArticleListVO> {
    /** FULLTEXT or LIKE; LIKE relevance requests fall back to newest. */
    private String searchMode;
    private String effectiveSort;
}
