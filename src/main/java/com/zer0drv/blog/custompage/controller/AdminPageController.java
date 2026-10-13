package com.zer0drv.blog.custompage.controller;

import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.custompage.dto.PageSaveDTO;
import com.zer0drv.blog.custompage.service.CustomPageService;
import com.zer0drv.blog.custompage.vo.PageVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 由 SecurityConfig 的 /admin/** 限制为管理员。 */
@RestController
@RequestMapping("/admin/pages")
@RequiredArgsConstructor
public class AdminPageController {
    private final CustomPageService pageService;
    @GetMapping
    public Result<PageResult<PageVO>> list(@RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "20") long size) {
        return Result.ok(pageService.adminList(page, size));
    }
    @GetMapping("/{id}")
    public Result<PageVO> detail(@PathVariable Long id) { return Result.ok(pageService.adminDetail(id)); }
    @PostMapping
    public Result<Long> create(@RequestBody @Valid PageSaveDTO dto) { return Result.ok(pageService.savePage(null, dto)); }
    @PutMapping("/{id}")
    public Result<Long> update(@PathVariable Long id, @RequestBody @Valid PageSaveDTO dto) {
        return Result.ok(pageService.savePage(id, dto));
    }
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) { pageService.deletePage(id); return Result.ok(); }
}
