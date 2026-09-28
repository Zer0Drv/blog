package com.zer0drv.blog.admin.controller;

import com.zer0drv.blog.admin.dto.SensitiveWordAddDTO;
import com.zer0drv.blog.admin.service.SensitiveWordService;
import com.zer0drv.blog.admin.vo.SensitiveWordVO;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 敏感词库管理（M5）。鉴权由 SecurityConfig 的 /admin/** hasRole(ADMIN) 覆盖，无需 @PreAuthorize。
 *
 * @author Yoruhaki
 */
@RestController
@RequestMapping("/admin/sensitive-words")
@RequiredArgsConstructor
public class AdminSensitiveWordController {

    private final SensitiveWordService sensitiveWordService;

    /**
     * 敏感词分页（keyword 模糊匹配）
     */
    @GetMapping
    public Result<PageResult<SensitiveWordVO>> page(@RequestParam(defaultValue = "1") long page,
                                                    @RequestParam(defaultValue = "50") long size,
                                                    @RequestParam(required = false) String keyword) {
        return Result.ok(sensitiveWordService.page(page, size, keyword));
    }

    /**
     * 新增敏感词（重复词静默成功）
     */
    @PostMapping
    public Result<Void> add(@RequestBody @Valid SensitiveWordAddDTO dto) {
        sensitiveWordService.add(dto.getWord());
        return Result.ok();
    }

    /**
     * 删除敏感词（逻辑删除）
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        sensitiveWordService.delete(id);
        return Result.ok();
    }
}
