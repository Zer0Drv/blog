package com.zer0drv.blog.custompage.controller;

import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.custompage.api.PageLink;
import com.zer0drv.blog.custompage.service.CustomPageService;
import com.zer0drv.blog.custompage.vo.PageVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class PageController {
    private final CustomPageService pageService;
    @GetMapping("/pages/{slug}")
    public Result<PageVO> detail(@PathVariable String slug) { return Result.ok(pageService.publicDetail(slug)); }
    @GetMapping("/site/page-navigation")
    public Result<List<PageLink>> navigation() { return Result.ok(pageService.navigation()); }
}
