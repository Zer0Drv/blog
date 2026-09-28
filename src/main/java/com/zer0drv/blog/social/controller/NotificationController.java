package com.zer0drv.blog.social.controller;

import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.social.service.NotificationService;
import com.zer0drv.blog.social.vo.NotificationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 统一通知中心（全部需登录）
 *
 * @author Yoruhaki
 */
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * 我的通知分页（create_time 倒序；type 可空按类型过滤）
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Result<PageResult<NotificationVO>> pageMine(@RequestParam(defaultValue = "1") long page,
                                                       @RequestParam(defaultValue = "10") long size,
                                                       @RequestParam(required = false) String type,
                                                       @AuthenticationPrincipal Jwt jwt) {
        return Result.ok(notificationService.pageMine(page, size, type, jwt));
    }

    /**
     * 我的未读通知数
     */
    @GetMapping("/unread-count")
    @PreAuthorize("isAuthenticated()")
    public Result<Map<String, Long>> unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return Result.ok(Map.of("count", notificationService.unreadCount(jwt)));
    }

    /**
     * 标记单条已读（仅本人，否则 40301）
     */
    @PutMapping("/{id}/read")
    @PreAuthorize("isAuthenticated()")
    public Result<Void> markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        notificationService.markRead(id, jwt);
        return Result.ok();
    }

    /**
     * 全部标记已读
     */
    @PutMapping("/read-all")
    @PreAuthorize("isAuthenticated()")
    public Result<Void> markAllRead(@AuthenticationPrincipal Jwt jwt) {
        notificationService.markAllRead(jwt);
        return Result.ok();
    }
}
