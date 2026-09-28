package com.zer0drv.blog.admin.service;

import com.zer0drv.blog.admin.dto.RecommendDTO;
import com.zer0drv.blog.admin.dto.TopDTO;
import com.zer0drv.blog.article.vo.ArticleListVO;
import com.zer0drv.blog.common.response.PageResult;

/**
 * 文章管理（M5，仅 ADMIN，由 SecurityConfig /admin/** 保护）。
 *
 * @author Yoruhaki
 */
public interface AdminArticleService {

    /**
     * 全状态文章分页。status 可空（DRAFT/PUBLISHED/OFFLINE）；keyword 模糊匹配 title/summary；authorId 可空。
     */
    PageResult<ArticleListVO> pageArticles(long page, long size, String status, String keyword, Long authorId);

    /**
     * 置顶切换（isTop 仅允许 0/1）
     */
    void updateTop(Long id, TopDTO dto);

    /**
     * 推荐位切换（isRecommended 仅允许 0/1）
     */
    void updateRecommend(Long id, RecommendDTO dto);

    /**
     * 强制下架（status=OFFLINE，不校验作者）
     */
    void offline(Long id);
}
