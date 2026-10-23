package com.zer0drv.blog.admin.controller;

import com.zer0drv.blog.admin.service.ApplicationRestartService;
import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.common.util.JwtSubjects;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/admin/operations/restart")
@RequiredArgsConstructor
public class ApplicationRestartController {
    private final ApplicationRestartService service;
    @GetMapping public Result<Map<String,Object>> capability() { return Result.ok(service.capability()); }
    @GetMapping("/{id}") public Result<Map<String,Object>> status(@PathVariable String id) { return Result.ok(service.status(id)); }
    @PostMapping @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Result<Map<String,Object>> restart(@AuthenticationPrincipal Jwt jwt, @RequestBody ApplicationRestartService.Request request) {
        return Result.ok(service.request(JwtSubjects.userIdOf(jwt), request));
    }
}
