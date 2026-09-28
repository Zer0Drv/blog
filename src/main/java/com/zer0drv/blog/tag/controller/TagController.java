package com.zer0drv.blog.tag.controller;

import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.tag.dto.TagSaveDTO;
import com.zer0drv.blog.tag.service.TagService;
import com.zer0drv.blog.tag.vo.TagVO;
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
@RequestMapping("/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;

    /**
     * 全部标签（公开）
     */
    @GetMapping
    public Result<List<TagVO>> listAll() {
        return Result.ok(tagService.listAll());
    }

    /**
     * 新建标签
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','AUTHOR')")
    public Result<Long> create(@RequestBody @Valid TagSaveDTO dto) {
        return Result.ok(tagService.create(dto));
    }

    /**
     * 编辑标签
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','AUTHOR')")
    public Result<Void> update(@PathVariable Long id, @RequestBody @Valid TagSaveDTO dto) {
        tagService.update(id, dto);
        return Result.ok();
    }

    /**
     * 删除标签
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','AUTHOR')")
    public Result<Void> delete(@PathVariable Long id) {
        tagService.delete(id);
        return Result.ok();
    }
}