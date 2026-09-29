package com.zer0drv.blog.article.vo;

import com.zer0drv.blog.tag.vo.TagVO;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文章列表项
 *
 * @author Yoruhaki
 */
@Data
public class ArticleListVO {

    private Long id;

    private String title;

    private String summary;

    private String cover;

    private Long categoryId;

    private String categoryName;

    private List<TagVO> tags;

    private ArticleAuthorVO author;

    private String status;

    /**
     * 置顶：0-否；1-是（M5，前端首页「置顶」标）
     */
    private Short isTop;

    /**
     * 推荐位：0-否；1-是（M5）
     */
    private Short isRecommended;

    private Long viewCount;

    private LocalDateTime publishTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}