package com.zer0drv.blog.admin.controller;

import com.zer0drv.blog.admin.dto.RoleUpdateDTO;
import com.zer0drv.blog.admin.service.AdminUserService;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.user.vo.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理（M5）。鉴权由 SecurityConfig 的 /admin/** hasRole(ADMIN) 覆盖，无需 @PreAuthorize。
 *
 * @author Yoruhaki
 */
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    /**
     * 用户分页（keyword 匹配 username/nickname/email；role/status 可空筛选）
     */
    @GetMapping
    public Result<PageResult<UserVO>> pageUsers(@RequestParam(defaultValue = "1") long page,
                                                @RequestParam(defaultValue = "10") long size,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String role,
                                                @RequestParam(required = false) String status) {
        return Result.ok(adminUserService.pageUsers(page, size, keyword, role, status));
    }

    /**
     * 封禁（不能封 ADMIN 40061、不能封自己 40062）
     */
    @PutMapping("/{id}/ban")
    public Result<Void> ban(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        adminUserService.ban(id, jwt);
        return Result.ok();
    }

    /**
     * 解封
     */
    @PutMapping("/{id}/unban")
    public Result<Void> unban(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        adminUserService.unban(id, jwt);
        return Result.ok();
    }

    /**
     * 角色变更（USER/AUTHOR/ADMIN 均可设为目标角色；不允许操作现有 ADMIN 账号、不允许改自己；
     * 变更后吊销目标全部 token，强制重新登录领取新角色）
     */
    @PutMapping("/{id}/role")
    public Result<Void> updateRole(@AuthenticationPrincipal Jwt jwt,
                                   @PathVariable Long id,
                                   @RequestBody @Valid RoleUpdateDTO dto) {
        adminUserService.updateRole(id, dto, jwt);
        return Result.ok();
    }
}
