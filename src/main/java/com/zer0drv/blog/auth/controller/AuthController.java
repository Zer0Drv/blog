package com.zer0drv.blog.auth.controller;

import com.zer0drv.blog.auth.dto.ChangePasswordDTO;
import com.zer0drv.blog.auth.dto.EmailCodeDTO;
import com.zer0drv.blog.auth.dto.OAuthExchangeDTO;
import com.zer0drv.blog.auth.dto.PasswordResetCodeDTO;
import com.zer0drv.blog.auth.dto.PasswordResetDTO;
import com.zer0drv.blog.auth.dto.RegisterDTO;
import com.zer0drv.blog.auth.dto.UserLoginDTO;
import com.zer0drv.blog.auth.oauth2.OAuthCodeStore;
import com.zer0drv.blog.auth.service.AuthCookieService;
import com.zer0drv.blog.auth.service.AuthService;
import com.zer0drv.blog.auth.service.EmailCodeService;
import com.zer0drv.blog.common.captcha.CaptchaGuard;
import com.zer0drv.blog.common.captcha.CaptchaService;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.common.util.IpUtils;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.vo.UserVO;
import io.github.linpeilie.Converter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

    /**
     * 登录按用户名维度计数的 sourceKey 前缀（#4：伪造/轮换来源 IP 无法绕过的兜底维度）
     */
    private static final String LOGIN_USER_KEY_PREFIX = "u:";

    private final AuthService authService;
    private final EmailCodeService emailCodeService;
    private final Converter converter;
    private final CaptchaGuard captchaGuard;
    private final AuthCookieService authCookieService;
    private final OAuthCodeStore oAuthCodeStore;

    /**
     * 发送注册邮箱验证码
     */
    @PostMapping("/email-code")
    public Result<Void> sendEmailCode(@RequestBody @Valid EmailCodeDTO dto, HttpServletRequest request) {
        String ip = IpUtils.clientIp(request);
        // 业务执行前校验图形验证码（未达阈值直接放行），发送尝试按来源 IP 计数
        captchaGuard.verifyAndRecord(CaptchaService.SCENE_EMAIL_CODE, ip, dto.getCaptchaId(), dto.getCaptchaCode());
        emailCodeService.sendCode(EmailCodeService.SCENE_REGISTER, dto.getEmail());
        return Result.ok();
    }

    /**
     * 邮箱验证码注册（成功即登录，返 token 并下发 HttpOnly Cookie）
     */
    @PostMapping("/register")
    public Result<Map<String, String>> register(@RequestBody @Valid RegisterDTO dto,
                                                HttpServletRequest request, HttpServletResponse response) {
        String ip = IpUtils.clientIp(request);
        // 业务执行前校验图形验证码（未达阈值直接放行），注册尝试按来源 IP 计数
        captchaGuard.verifyAndRecord(CaptchaService.SCENE_REGISTER, ip, dto.getCaptchaId(), dto.getCaptchaCode());
        Map<String, String> tokens = authService.register(dto);
        // blog-ui#13 契约第 1 条：注册成功统一下发 HttpOnly Cookie（响应体 access_token 过渡期保留）
        authCookieService.writeTokenCookie(response, tokens.get("access_token"));
        return Result.ok(tokens);
    }

    /**
     * 账号密码登录（成功返 token 并下发 HttpOnly Cookie）
     */
    @PostMapping("/login")
    public Result<Map<String, String>> userLogin(@RequestBody @Valid UserLoginDTO dto,
                                                 HttpServletRequest request, HttpServletResponse response) {
        String ip = IpUtils.clientIp(request);
        // #4 兜底：IP 维度之外按用户名维度独立计数，伪造 XFF / 轮换代理 IP 无法绕过
        String userKey = LOGIN_USER_KEY_PREFIX + dto.getUsername();
        // 任一维度达阈值即强制校验图形验证码；登录成功清零双维度，失败（含验证码缺失/错误）双维度计数
        Map<String, String> tokens = captchaGuard.guarded(CaptchaService.SCENE_LOGIN, ip, userKey,
                dto.getCaptchaId(), dto.getCaptchaCode(), () -> authService.userLogin(dto));
        // blog-ui#13 契约第 1 条：登录成功统一下发 HttpOnly Cookie（响应体 access_token 过渡期保留）
        authCookieService.writeTokenCookie(response, tokens.get("access_token"));
        return Result.ok(tokens);
    }

    /**
     * 登出（jti 黑名单复用现有机制；blog-ui#13 契约第 5 条：同时清除认证 Cookie）
     */
    @PostMapping("/logout")
    public Result<String> userLogout(@AuthenticationPrincipal Jwt jwt, HttpServletResponse response) {
        authCookieService.clearTokenCookie(response);
        return Result.ok(authService.userLogout(jwt));
    }

    /**
     * 当前登录用户信息（blog-ui#13 契约第 7 条：前端用它判断登录态）
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
     * 找回密码：发送验证码。
     * #3：补齐与同类端点一致的图形验证码计数校验；
     * #9：无论邮箱是否注册都返回相同成功文案（不泄露注册状态），仅已注册邮箱实际发码。
     */
    @PostMapping("/password-reset-code")
    public Result<Void> sendPasswordResetCode(@RequestBody @Valid PasswordResetCodeDTO dto,
                                              HttpServletRequest request) {
        String ip = IpUtils.clientIp(request);
        // 业务执行前校验图形验证码（未达阈值直接放行），发码尝试按来源 IP 计数
        captchaGuard.verifyAndRecord(CaptchaService.SCENE_PASSWORD_RESET, ip, dto.getCaptchaId(), dto.getCaptchaCode());
        authService.sendPasswordResetCode(dto.getEmail());
        Result<Void> result = Result.ok();
        result.setMessage("若该邮箱已注册，验证码已发送");
        return result;
    }

    /**
     * 找回密码：验证码 + 新密码重置（成功后该用户全部历史 token 吊销，见 #9）
     */
    @PostMapping("/password-reset")
    public Result<Void> resetPassword(@RequestBody @Valid PasswordResetDTO dto, HttpServletRequest request) {
        String ip = IpUtils.clientIp(request);
        // 业务执行前校验图形验证码（未达阈值直接放行），重置尝试按来源 IP 计数
        captchaGuard.verifyAndRecord(CaptchaService.SCENE_PASSWORD_RESET, ip, dto.getCaptchaId(), dto.getCaptchaCode());
        authService.resetPassword(dto);
        return Result.ok();
    }

    /**
     * 修改密码（成功后该用户全部会话需重新登录，见 #9）
     */
    @PutMapping("/password")
    public Result<Void> changePassword(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid ChangePasswordDTO dto) {
        authService.changePassword(jwt, dto);
        return Result.ok();
    }

    /**
     * OAuth 一次性换码（blog-ui#13 契约第 2 条）：回调页拿 code 换 HttpOnly Cookie；
     * 响应体仅含 expiresIn，不含 token
     */
    @PostMapping("/oauth/exchange")
    public Result<Map<String, Long>> exchangeOAuthCode(@RequestBody @Valid OAuthExchangeDTO dto,
                                                       HttpServletResponse response) {
        String token = oAuthCodeStore.consume(dto.getCode());
        if (Objects.isNull(token)) {
            throw new BusinessException(StatusCode.OAUTH_CODE_INVALID);
        }
        authCookieService.writeTokenCookie(response, token);
        return Result.ok(Map.of("expiresIn", authCookieService.getExpiresInSeconds()));
    }
}
