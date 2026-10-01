package com.zer0drv.blog.auth.oauth2;

import com.zer0drv.blog.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * GitHub OAuth 登录成功处理器（blog-ui#13 契约第 2 条）：换发本站 JWT 后不再 302 回跳携带 token，
 * 改为生成一次性 code（Redis oauth:code:<code> → token，TTL 60 秒，消费即删），
 * 302 回前端回调页 /oauth/callback?code=<code>；前端再调 POST /auth/oauth/exchange 换 HttpOnly Cookie。
 *
 * @author Yoruhaki
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final AuthService authService;
    private final OAuthCodeStore oAuthCodeStore;

    /**
     * 前端 OAuth 回调地址（一次性 code 以 query 传递，前端即刻调 /auth/oauth/exchange 消费）
     */
    @Value("${blog.oauth.success-redirect:http://localhost:5173/oauth/callback}")
    private String successRedirect;

    /**
     * 失败回跳（successHandler 不走 @RestControllerAdvice，业务异常在此捕获后统一回跳）
     */
    @Value("${blog.oauth.failure-redirect:http://localhost:5173/login?error=oauth_failed}")
    private String failureRedirect;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        try {
            OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
            Map<String, Object> attributes = oAuth2User.getAttributes();
            // GitHub id 为数值型（Integer/Long 均可能出现）
            Object idAttr = attributes.get("id");
            Long githubId = idAttr instanceof Number number ? number.longValue() : null;
            Map<String, String> token = authService.loginByGithub(
                    githubId,
                    strAttr(attributes.get("login")),
                    strAttr(attributes.get("name")),
                    strAttr(attributes.get("avatar_url")),
                    strAttr(attributes.get("email")));
            // 一次性换码：token 不再经 URL 传递
            String code = oAuthCodeStore.store(token.get("access_token"));
            String target = successRedirect + "?code="
                    + URLEncoder.encode(code, StandardCharsets.UTF_8);
            response.sendRedirect(target);
        } catch (Exception e) {
            // 封禁/撞键等业务异常：不能走 @RestControllerAdvice（此处不在 MVC 流程），回跳前端提示
            log.warn("GitHub OAuth 登录失败: {}", e.getMessage());
            response.sendRedirect(failureRedirect);
        }
    }

    private String strAttr(Object value) {
        return Objects.isNull(value) ? null : value.toString();
    }
}
