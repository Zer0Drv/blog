package com.zer0drv.blog.sysconfig.controller;

import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.util.JwtSubjects;
import com.zer0drv.blog.sysconfig.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/sys/config")
@RequiredArgsConstructor
public class AdminSysConfigController {
    private final SysConfigService service;

    @GetMapping
    public Result<List<Map<String, Object>>> list() { return Result.ok(service.list()); }

    @PutMapping
    public Result<Void> update(@RequestBody Map<String, Object> values, @AuthenticationPrincipal Jwt jwt) {
        service.update(values, JwtSubjects.userIdOf(jwt));
        return Result.ok();
    }

    @DeleteMapping("/{key}")
    public Result<Void> reset(@PathVariable String key, @AuthenticationPrincipal Jwt jwt) {
        service.reset(key, JwtSubjects.userIdOf(jwt));
        return Result.ok();
    }

    @GetMapping("/audit")
    public Result<PageResult<Map<String, Object>>> audit(@RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") long size) {
        return Result.ok(service.audit(page, size));
    }
}
