package com.zer0drv.blog.auth.controller;

import com.zer0drv.blog.auth.dto.ChangePasswordDTO;
import com.zer0drv.blog.auth.dto.EmailCodeDTO;
import com.zer0drv.blog.auth.dto.RegisterDTO;
import com.zer0drv.blog.auth.dto.UserLoginDTO;
import com.zer0drv.blog.auth.service.AuthService;
import com.zer0drv.blog.auth.service.EmailCodeService;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.vo.UserVO;
import io.github.linpeilie.Converter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Objects;

/**
 * @author Yoruhaki
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final EmailCodeService emailCodeService;
    private final Converter converter;

    /**
     * 发送注册邮箱验证码
     */
    @PostMapping("/email-code")
    public Result<Void> sendEmailCode(@RequestBody @Valid EmailCodeDTO dto) {
        emailCodeService.sendCode(EmailCodeService.SCENE_REGISTER, dto.getEmail());
        return Result.ok();
    }

    /**
     * 邮箱验证码注册（成功即登录，直接返 token）
     */
    @PostMapping("/register")
    public Result<Map<String, String>> register(@RequestBody @Valid RegisterDTO dto) {
        return Result.ok(authService.register(dto));
    }

    /**
     * 账号密码登录
     */
    @PostMapping("/login")
    public Result<Map<String, String>> userLogin(@RequestBody @Valid UserLoginDTO dto) {
        return Result.ok(authService.userLogin(dto));
    }

    /**
     * 登出
     */
    @PostMapping("/logout")
    public Result<String> userLogout(@AuthenticationPrincipal Jwt jwt) {
        return Result.ok(authService.userLogout(jwt));
    }

    /**
     * 当前登录用户信息
     */
    @GetMapping("/me")
    public Result<UserVO> getProfile(@AuthenticationPrincipal Jwt jwt) {
        User profile = authService.getProfile(jwt);
        if (Objects.isNull(profile)) {
            throw new BusinessException(StatusCode.USER_NOT_EXIST);
        }
        return Result.ok(converter.convert(profile, UserVO.class));
    }

    /**
     * 修改密码（成功后需重新登录）
     */
    @PutMapping("/password")
    public Result<Void> changePassword(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid ChangePasswordDTO dto) {
        authService.changePassword(jwt, dto);
        return Result.ok();
    }
}
