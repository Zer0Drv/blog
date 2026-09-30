package com.zer0drv.blog.admin.controller;

import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.site.service.SiteConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 站点设置中心（管理端）。鉴权由 SecurityConfig 的 /admin/** hasRole(ADMIN) 覆盖。
 *
 * @author Yoruhaki
 */
@RestController
@RequestMapping("/admin/site")
@RequiredArgsConstructor
public class AdminSiteController {

    private final SiteConfigService siteConfigService;

    /**
     * 全量配置（含非公开键 site.base_url / comment.review_required）
     */
    @GetMapping("/config")
    public Result<Map<String, String>> getAll() {
        return Result.ok(siteConfigService.getAll());
    }

    /**
     * 批量保存（仅接受已知键，未知键忽略），返回最新全量配置
     */
    @PutMapping("/config")
    public Result<Map<String, String>> setAll(@RequestBody Map<String, String> values) {
        siteConfigService.setAll(values);
        return Result.ok(siteConfigService.getAll());
    }
}
