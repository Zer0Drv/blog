package com.zer0drv.blog.article.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.zer0drv.blog.article.domain.Article;
import com.zer0drv.blog.article.dto.ArticleSaveDTO;
import com.zer0drv.blog.article.dto.ArticleStatusDTO;
import com.zer0drv.blog.article.vo.ArticleDetailVO;
import com.zer0drv.blog.article.vo.ArticleListVO;
import com.zer0drv.blog.common.response.PageResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * @author Yoruhaki
 */
public interface ArticleService extends IService<Article> {

    /**
     * 已发布文章分页列表（按发布时间倒序，支持关键字 / 标签 / 分类过滤）
     */
    PageResult<ArticleListVO> pagePublished(long page, long size, String keyword, Long tagId, Long categoryId);

    /**
     * 文章详情。匿名 / 非作者仅可见 PUBLISHED；作者本人或 ADMIN 可看任意状态。
     * jwt 可为 null（匿名访问）。
     */
    ArticleDetailVO getDetail(Long id, Jwt jwt);

    /**
     * 新建文章（允许直接发布），返回文章id
     */
    Long create(ArticleSaveDTO dto, Jwt jwt);

    /**
     * 编辑文章（仅本人或 ADMIN）
     */
    void update(Long id, ArticleSaveDTO dto, Jwt jwt);

    /**
     * 删除文章（仅本人或 ADMIN，逻辑删除）
     */
    void delete(Long id, Jwt jwt);

    /**
     * 上架 / 下架 / 回草稿（仅本人或 ADMIN；首次发布写 publish_time）
     */
    void updateStatus(Long id, ArticleStatusDTO dto, Jwt jwt);

    /**
     * 本人文章分页（含草稿 / 下架，status 可空）
     */
    PageResult<ArticleListVO> pageMine(long page, long size, String status, Jwt jwt);
}