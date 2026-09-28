package com.zer0drv.blog.admin.controller;

import com.zer0drv.blog.admin.service.AdminStatsService;
import com.zer0drv.blog.admin.vo.StatsOverviewVO;
import com.zer0drv.blog.admin.vo.StatsRecentVO;
import com.zer0drv.blog.common.response.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站点数据 dashboard（M5）。鉴权由 SecurityConfig 的 /admin/** hasRole(ADMIN) 覆盖，无需 @PreAuthorize。
 *
 * @author Yoruhaki
 */
@RestController
@RequestMapping("/admin/stats")
@RequiredArgsConstructor
public class AdminStatsController {

    private final AdminStatsService adminStatsService;

    /**
     * 站点数据总览
     */
    @GetMapping("/overview")
    public Result<StatsOverviewVO> overview() {
        return Result.ok(adminStatsService.overview());
    }

    /**
     * 最新动态（最新用户 / 文章 / 评论各 5 条）
     */
    @GetMapping("/recent")
    public Result<StatsRecentVO> recent() {
        return Result.ok(adminStatsService.recent());
    }
}
