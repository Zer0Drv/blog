package com.zer0drv.blog.admin.controller;

import com.zer0drv.blog.admin.dto.RecommendDTO;
import com.zer0drv.blog.admin.dto.TopDTO;
import com.zer0drv.blog.admin.service.AdminArticleService;
import com.zer0drv.blog.article.vo.ArticleListVO;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文章管理（M5）。鉴权由 SecurityConfig 的 /admin/** hasRole(ADMIN) 覆盖，无需 @PreAuthorize。
 *
 * @author Yoruhaki
 */
@RestController
@RequestMapping("/admin/articles")
@RequiredArgsConstructor
public class AdminArticleController {

    private final AdminArticleService adminArticleService;

    /**
     * 全状态文章分页（status/keyword/authorId 可空筛选）
     */
    @GetMapping
    public Result<PageResult<ArticleListVO>> pageArticles(@RequestParam(defaultValue = "1") long page,
                                                          @RequestParam(defaultValue = "10") long size,
                                                          @RequestParam(required = false) String status,
                                                          @RequestParam(required = false) String keyword,
                                                          @RequestParam(required = false) Long authorId) {
        return Result.ok(adminArticleService.pageArticles(page, size, status, keyword, authorId));
    }

    /**
     * 置顶切换
     */
    @PutMapping("/{id}/top")
    public Result<Void> updateTop(@PathVariable Long id, @RequestBody @Valid TopDTO dto) {
        adminArticleService.updateTop(id, dto);
        return Result.ok();
    }

    /**
     * 推荐位切换
     */
    @PutMapping("/{id}/recommend")
    public Result<Void> updateRecommend(@PathVariable Long id, @RequestBody @Valid RecommendDTO dto) {
        adminArticleService.updateRecommend(id, dto);
        return Result.ok();
    }

    /**
     * 强制下架（status=OFFLINE，不校验作者）
     */
    @PutMapping("/{id}/offline")
    public Result<Void> offline(@PathVariable Long id) {
        adminArticleService.offline(id);
        return Result.ok();
    }
}
