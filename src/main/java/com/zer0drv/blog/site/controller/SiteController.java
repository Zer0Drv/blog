package com.zer0drv.blog.site.controller;

import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.site.service.impl.SiteConfigServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 站点公开配置（匿名可读，SecurityConfig 已 permitAll GET /site/config）。
 * 只出白名单键：site.name/site.description/site.logo/site.icp/site.footer。
 *
 * @author Yoruhaki
 */
@RestController
@RequestMapping("/site")
@RequiredArgsConstructor
public class SiteController {

    private final SiteConfigServiceImpl siteConfigService;

    /**
     * 公开站点配置（白名单 5 键；site.base_url 与 comment.review_required 不外泄）
     */
    @GetMapping("/config")
    public Result<Map<String, String>> publicConfig() {
        return Result.ok(siteConfigService.getPublic());
    }
}
