package com.zer0drv.blog.article.series.controller;

import com.zer0drv.blog.article.series.dto.SeriesSaveDTO;
import com.zer0drv.blog.article.series.service.SeriesService;
import com.zer0drv.blog.article.series.vo.SeriesViews.*;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/series")
@RequiredArgsConstructor
public class SeriesController {
    private final SeriesService service;

    @GetMapping
    public Result<PageResult<Summary>> list(@RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(service.publicList(page, size));
    }

    @GetMapping("/{slug}")
    public Result<Detail> detail(@PathVariable String slug) { return Result.ok(service.publicDetail(slug)); }

    @GetMapping("/by-article/{id}")
    public Result<List<Membership>> byArticle(@PathVariable long id) { return Result.ok(service.byArticle(id)); }

    @GetMapping("/manage")
    @PreAuthorize("hasAnyRole('ADMIN', 'AUTHOR')")
    public Result<PageResult<ManagedSummary>> manageList(@RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size, @RequestParam(defaultValue = "false") boolean all,
            @AuthenticationPrincipal Jwt jwt) { return Result.ok(service.manageList(page, size, all, jwt)); }

    @GetMapping("/manage/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'AUTHOR')")
    public Result<ManagedDetail> manageDetail(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return Result.ok(service.manageDetail(id, jwt));
    }

    @GetMapping("/manage/candidates")
    @PreAuthorize("hasAnyRole('ADMIN', 'AUTHOR')")
    public Result<PageResult<ManagedChapter>> candidates(@RequestParam(required = false) Long seriesId,
            @RequestParam(defaultValue = "") String keyword, @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size, @AuthenticationPrincipal Jwt jwt) {
        return Result.ok(service.candidates(seriesId, keyword, page, size, jwt));
    }

    @PostMapping("/manage")
    @PreAuthorize("hasAnyRole('ADMIN', 'AUTHOR')")
    public Result<ManagedDetail> create(@Valid @RequestBody SeriesSaveDTO dto, @AuthenticationPrincipal Jwt jwt) {
        return Result.ok(service.save(null, dto, jwt));
    }

    @PutMapping("/manage/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'AUTHOR')")
    public Result<ManagedDetail> update(@PathVariable long id, @Valid @RequestBody SeriesSaveDTO dto,
                                       @AuthenticationPrincipal Jwt jwt) { return Result.ok(service.save(id, dto, jwt)); }

    @DeleteMapping("/manage/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'AUTHOR')")
    public Result<Void> delete(@PathVariable long id, @RequestParam long revision, @AuthenticationPrincipal Jwt jwt) {
        service.delete(id, revision, jwt);
        return Result.ok();
    }
}
