package com.zer0drv.blog.article.controller;

import com.zer0drv.blog.article.dto.ArticleSaveDTO;
import com.zer0drv.blog.article.dto.ArticleStatusDTO;
import com.zer0drv.blog.article.dto.AutosaveDTO;
import com.zer0drv.blog.article.service.ArticleService;
import com.zer0drv.blog.article.vo.ArchiveMonthVO;
import com.zer0drv.blog.article.vo.ArticleDetailVO;
import com.zer0drv.blog.article.vo.ArticleListVO;
import com.zer0drv.blog.article.vo.ArticleVersionDetailVO;
import com.zer0drv.blog.article.vo.ArticleVersionVO;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * @author Yoruhaki
 */
@RestController
@RequestMapping("/articles")
@RequiredArgsConstructor
public class ArticleController {

    private final ArticleService articleService;

    /**
     * 已发布文章分页列表（公开）
     */
    @GetMapping
    public Result<PageResult<ArticleListVO>> pagePublished(@RequestParam(defaultValue = "1") long page,
                                                           @RequestParam(defaultValue = "10") long size,
                                                           @RequestParam(required = false) String keyword,
                                                           @RequestParam(required = false) Long tagId,
                                                           @RequestParam(required = false) Long categoryId) {
        return Result.ok(articleService.pagePublished(page, size, keyword, tagId, categoryId));
    }

    /**
     * 本人文章分页（含草稿 / 下架）
     */
    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('ADMIN','AUTHOR')")
    public Result<PageResult<ArticleListVO>> pageMine(@RequestParam(defaultValue = "1") long page,
                                                      @RequestParam(defaultValue = "10") long size,
                                                      @RequestParam(required = false) String status,
                                                      @AuthenticationPrincipal Jwt jwt) {
        return Result.ok(articleService.pageMine(page, size, status, jwt));
    }

    /**
     * 全文搜索（公开；P0）。字面量路径优先于 /{id}，无需改 SecurityConfig
     */
    @GetMapping("/search")
    public Result<PageResult<ArticleListVO>> search(@RequestParam(required = false) String keyword,
                                                    @RequestParam(defaultValue = "1") long page,
                                                    @RequestParam(defaultValue = "10") long size) {
        return Result.ok(articleService.search(keyword, page, size));
    }

    /**
     * 归档（公开；P0）：可见文章按月分组
     */
    @GetMapping("/archives")
    public Result<List<ArchiveMonthVO>> archives() {
        return Result.ok(articleService.archives());
    }

    /**
     * 文章详情（公开；非发布状态仅作者本人 / ADMIN 可见）
     */
    @GetMapping("/{id}")
    public Result<ArticleDetailVO> getDetail(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return Result.ok(articleService.getDetail(id, jwt));
    }

    /**
     * 新建文章（允许直接发布）
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','AUTHOR')")
    public Result<Long> create(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid ArticleSaveDTO dto) {
        return Result.ok(articleService.create(dto, jwt));
    }

    /**
     * 编辑文章（仅本人或 ADMIN）
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','AUTHOR')")
    public Result<Void> update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                               @RequestBody @Valid ArticleSaveDTO dto) {
        articleService.update(id, dto, jwt);
        return Result.ok();
    }

    /**
     * 删除文章（仅本人或 ADMIN）
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','AUTHOR')")
    public Result<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        articleService.delete(id, jwt);
        return Result.ok();
    }

    /**
     * 上架 / 下架 / 回草稿（仅本人或 ADMIN）
     */
    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN','AUTHOR')")
    public Result<Void> updateStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                     @RequestBody @Valid ArticleStatusDTO dto) {
        articleService.updateStatus(id, dto, jwt);
        return Result.ok();
    }

    /**
     * 版本列表（P0，仅本人或 ADMIN；version 倒序，不含正文）
     */
    @GetMapping("/{id}/versions")
    @PreAuthorize("hasAnyRole('ADMIN','AUTHOR')")
    public Result<List<ArticleVersionVO>> listVersions(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return Result.ok(articleService.listVersions(id, jwt));
    }

    /**
     * 版本详情（P0，仅本人或 ADMIN）
     */
    @GetMapping("/{id}/versions/{version}")
    @PreAuthorize("hasAnyRole('ADMIN','AUTHOR')")
    public Result<ArticleVersionDetailVO> getVersion(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                                     @PathVariable Integer version) {
        return Result.ok(articleService.getVersion(id, version, jwt));
    }

    /**
     * 恢复到指定版本（P0，仅本人或 ADMIN；恢复前对当前行留快照）
     */
    @PostMapping("/{id}/restore/{version}")
    @PreAuthorize("hasAnyRole('ADMIN','AUTHOR')")
    public Result<Void> restoreVersion(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                       @PathVariable Integer version) {
        articleService.restoreVersion(id, version, jwt);
        return Result.ok();
    }

    /**
     * 自动保存草稿（P0，仅本人或 ADMIN，仅编辑已有文章）
     */
    @PutMapping("/{id}/autosave")
    @PreAuthorize("hasAnyRole('ADMIN','AUTHOR')")
    public Result<Map<String, Object>> saveAutosave(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                                    @RequestBody @Valid AutosaveDTO dto) {
        return Result.ok(articleService.saveAutosave(id, dto, jwt));
    }

    /**
     * 读取自动保存草稿（P0，仅本人或 ADMIN）
     */
    @GetMapping("/{id}/autosave")
    @PreAuthorize("hasAnyRole('ADMIN','AUTHOR')")
    public Result<Map<String, Object>> getAutosave(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return Result.ok(articleService.getAutosave(id, jwt));
    }

    /**
     * 从回收站恢复（P0，仅本人或 ADMIN；回到草稿态）
     */
    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAnyRole('ADMIN','AUTHOR')")
    public Result<Void> restore(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        articleService.restore(id, jwt);
        return Result.ok();
    }

    /**
     * 彻底删除（P0，仅本人或 ADMIN；物理删除并级联清理，不可恢复）
     */
    @DeleteMapping("/{id}/force")
    @PreAuthorize("hasAnyRole('ADMIN','AUTHOR')")
    public Result<Void> forceDelete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        articleService.forceDelete(id, jwt);
        return Result.ok();
    }
}