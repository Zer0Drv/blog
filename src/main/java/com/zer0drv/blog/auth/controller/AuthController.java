package com.zer0drv.blog.auth.controller;

import com.zer0drv.blog.auth.dto.ChangePasswordDTO;
import com.zer0drv.blog.auth.dto.EmailCodeDTO;
import com.zer0drv.blog.auth.dto.PasswordResetCodeDTO;
import com.zer0drv.blog.auth.dto.PasswordResetDTO;
import com.zer0drv.blog.auth.dto.RegisterDTO;
import com.zer0drv.blog.auth.dto.UserLoginDTO;
import com.zer0drv.blog.auth.service.AuthService;
import com.zer0drv.blog.auth.service.EmailCodeService;
import com.zer0drv.blog.common.captcha.CaptchaService;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.common.util.IpUtils;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.vo.UserVO;
import io.github.linpeilie.Converter;
import jakarta.servlet.http.HttpServletRequest;
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
    private final CaptchaService captchaService;

    /**
     * 发送注册邮箱验证码
     */
    @PostMapping("/email-code")
    public Result<Void> sendEmailCode(@RequestBody @Valid EmailCodeDTO dto, HttpServletRequest request) {
        String ip = IpUtils.clientIp(request);
        // 业务执行前校验图形验证码（未达阈值直接放行），发送尝试按来源 IP 计数
        captchaService.verify(CaptchaService.SCENE_EMAIL_CODE, ip, dto.getCaptchaId(), dto.getCaptchaCode());
        captchaService.recordAttempt(CaptchaService.SCENE_EMAIL_CODE, ip);
        emailCodeService.sendCode(EmailCodeService.SCENE_REGISTER, dto.getEmail());
        return Result.ok();
    }

    /**
     * 邮箱验证码注册（成功即登录，直接返 token）
     */
    @PostMapping("/register")
    public Result<Map<String, String>> register(@RequestBody @Valid RegisterDTO dto, HttpServletRequest request) {
        String ip = IpUtils.clientIp(request);
        // 业务执行前校验图形验证码（未达阈值直接放行），注册尝试按来源 IP 计数
        captchaService.verify(CaptchaService.SCENE_REGISTER, ip, dto.getCaptchaId(), dto.getCaptchaCode());
        captchaService.recordAttempt(CaptchaService.SCENE_REGISTER, ip);
        return Result.ok(authService.register(dto));
    }

    /**
     * 账号密码登录
     */
    @PostMapping("/login")
    public Result<Map<String, String>> userLogin(@RequestBody @Valid UserLoginDTO dto, HttpServletRequest request) {
        String ip = IpUtils.clientIp(request);
        try {
            // 业务执行前校验图形验证码（未达阈值直接放行）
            captchaService.verify(CaptchaService.SCENE_LOGIN, ip, dto.getCaptchaId(), dto.getCaptchaCode());
            Map<String, String> tokens = authService.userLogin(dto);
            // 登录成功清零计数
            captchaService.clearAttempts(CaptchaService.SCENE_LOGIN, ip);
            return Result.ok(tokens);
        } catch (BusinessException e) {
            // 登录失败（含验证码缺失/错误）按来源 IP 计数
            captchaService.recordAttempt(CaptchaService.SCENE_LOGIN, ip);
            throw e;
        }
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
     * 找回密码：发送验证码（邮箱未注册 → 40065）
     */
    @PostMapping("/password-reset-code")
    public Result<Void> sendPasswordResetCode(@RequestBody @Valid PasswordResetCodeDTO dto) {
        authService.sendPasswordResetCode(dto.getEmail());
        return Result.ok();
    }

    /**
     * 找回密码：验证码 + 新密码重置（历史 token 不作废，到期自然失效）
     */
    @PostMapping("/password-reset")
    public Result<Void> resetPassword(@RequestBody @Valid PasswordResetDTO dto, HttpServletRequest request) {
        String ip = IpUtils.clientIp(request);
        // 业务执行前校验图形验证码（未达阈值直接放行），重置尝试按来源 IP 计数
        captchaService.verify(CaptchaService.SCENE_PASSWORD_RESET, ip, dto.getCaptchaId(), dto.getCaptchaCode());
        captchaService.recordAttempt(CaptchaService.SCENE_PASSWORD_RESET, ip);
        authService.resetPassword(dto);
        return Result.ok();
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
