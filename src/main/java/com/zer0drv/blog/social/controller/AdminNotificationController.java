package com.zer0drv.blog.social.controller;

import com.zer0drv.blog.social.service.NotificationService;
import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.common.util.JwtSubjects;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

@RestController
@RequestMapping("/admin/notifications")
@RequiredArgsConstructor
public class AdminNotificationController {
    private final NotificationService notifications;
    @GetMapping
    public Result<java.util.List<com.zer0drv.blog.social.vo.NotificationVO>> forComment(@RequestParam Long commentId) {
        return Result.ok(notifications.forComment(commentId));
    }
    @DeleteMapping("/{id}")
    public Result<Void> retract(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        notifications.retract(id, JwtSubjects.userIdOf(jwt));
        return Result.ok();
    }
}
