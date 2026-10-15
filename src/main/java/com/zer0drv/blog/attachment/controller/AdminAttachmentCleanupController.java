package com.zer0drv.blog.attachment.controller;

import com.zer0drv.blog.attachment.service.AttachmentCleanupService;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.common.util.JwtSubjects;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/admin/attachments/cleanup")
@RequiredArgsConstructor
public class AdminAttachmentCleanupController {
    private final AttachmentCleanupService service;
    @GetMapping public Result<PageResult<Map<String, Object>>> page(@RequestParam(defaultValue="1") long page, @RequestParam(defaultValue="20") long size) {
        return Result.ok(service.page(page, size));
    }
    @GetMapping("/report") public Result<Map<String, Object>> report() { return Result.ok(service.report()); }
    @PostMapping("/scan") public Result<Map<String, Object>> scan(@AuthenticationPrincipal Jwt jwt) { return Result.ok(service.scan(JwtSubjects.userIdOf(jwt))); }
    @PostMapping("/{id}/restore") public Result<Void> restore(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        service.restore(id, JwtSubjects.userIdOf(jwt)); return Result.ok();
    }
    @DeleteMapping("/{id}") public Result<Map<String, String>> purge(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return Result.ok(Map.of("state", service.purge(id, JwtSubjects.userIdOf(jwt))));
    }
}
