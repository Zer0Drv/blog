package com.zer0drv.blog.user.controller;

import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.common.util.JwtSubjects;
import com.zer0drv.blog.user.dto.ProfileUpdateDTO;
import com.zer0drv.blog.user.service.UserService;
import com.zer0drv.blog.user.vo.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 用户个人资料接口（登录后可访问，由 SecurityConfig anyRequest().authenticated() 拦截）
 *
 * @author Yoruhaki
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 更新当前登录用户个人资料（昵称/头像/简介）
     */
    @PutMapping("/me")
    public Result<UserVO> updateProfile(@AuthenticationPrincipal Jwt jwt,
                                        @RequestBody @Valid ProfileUpdateDTO dto) {
        Long userId = JwtSubjects.userIdOf(jwt);
        return Result.ok(userService.updateProfile(userId, dto));
    }

    /**
     * 查询当前登录用户通知偏好（P0 §2.3）→ {emailNotifyEnabled: true|false}
     */
    @GetMapping("/me/preferences")
    public Result<Map<String, Boolean>> getPreferences(@AuthenticationPrincipal Jwt jwt) {
        return Result.ok(userService.getPreferences(JwtSubjects.userIdOf(jwt)));
    }

    /**
     * 更新当前登录用户通知偏好（P0 §2.3）：body {emailNotifyEnabled: boolean}（必传，非布尔 → PARAM_INVALID）
     */
    @PutMapping("/me/preferences")
    public Result<Map<String, Boolean>> updatePreferences(@AuthenticationPrincipal Jwt jwt,
                                                          @RequestBody(required = false) Map<String, Object> preferences) {
        return Result.ok(userService.updatePreferences(JwtSubjects.userIdOf(jwt), preferences));
    }
}
