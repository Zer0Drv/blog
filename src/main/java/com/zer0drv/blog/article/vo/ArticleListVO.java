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

    /**
     * 固定链接 slug（issue #14）：公众端 /article/{slug} 路由与 RSS/sitemap 链接使用
     */
    private String slug;

    private String summary;

    /** Public search excerpt, plain text; absent on other list endpoints. */
    private String snippet;

    /** Literal terms present in this public search title/excerpt; never HTML or raw SQL offsets. */
    private List<String> highlightTerms;

    private String cover;

    private Long categoryId;

    private String categoryName;

    private List<TagVO> tags;

    private ArticleAuthorVO author;

    private String status;

    /**
     * 可见性（issue #15）：PUBLIC / PASSWORD / PRIVATE。公开列表恒为 PUBLIC
     * （谓词只放 PUBLIC 出来）；我的文章 / 管理端列表可见真实值，前端据此展示标记
     */
    private String visibility;

    /**
     * 当前是否对外可见（PUBLISHED 且 publish_time 非空且到点 且 visibility=PUBLIC），谓词唯一出处
     * {@code ArticleVisibility}，装配层统一填充。公开列表中恒为 true；
     * 收藏夹等「本人的清单」不过滤可见性，已下架 / 定时中的条目为 false，
     * 前端据此置灰。逻辑删除不在本字段判定之列：回收站视图请忽略本字段。
     */
    private Boolean visible;

    /**
     * 编辑器类型：MARKDOWN / RICHTEXT（前端列表行内导出按它决定 md/html）
     */
    private String editorType;

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
