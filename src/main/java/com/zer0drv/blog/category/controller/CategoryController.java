package com.zer0drv.blog.category.controller;

import com.zer0drv.blog.category.dto.CategorySaveDTO;
import com.zer0drv.blog.category.service.CategoryService;
import com.zer0drv.blog.category.vo.CategoryVO;
import com.zer0drv.blog.common.response.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @author Yoruhaki
 */
@RestController
@RequestMapping("/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    /**
     * 分类树（公开）
     */
    @GetMapping
    public Result<List<CategoryVO>> tree() {
        return Result.ok(categoryService.tree());
    }

    /**
     * 新建分类
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Long> create(@RequestBody @Valid CategorySaveDTO dto) {
        return Result.ok(categoryService.create(dto));
    }

    /**
     * 编辑分类
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> update(@PathVariable Long id, @RequestBody @Valid CategorySaveDTO dto) {
        categoryService.update(id, dto);
        return Result.ok();
    }

    /**
     * 删除分类（有子分类或文章引用时拒绝）
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return Result.ok();
    }
}