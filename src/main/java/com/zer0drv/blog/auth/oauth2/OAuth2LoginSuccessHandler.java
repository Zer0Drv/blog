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
 * GitHub OAuth 登录成功处理器：换发本站 JWT 后 302 回前端回调页（query 携带 token）。
 *
 * @author Yoruhaki
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final AuthService authService;

    /**
     * 前端 OAuth 回调地址（token 以 query 传递，前端存 localStorage 后走既有 Bearer 流程）
     */
    @Value("${blog.oauth.success-redirect:http://localhost:5173/oauth/callback}")
    private String successRedirect;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
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
        String target = successRedirect + "?token="
                + URLEncoder.encode(token.get("access_token"), StandardCharsets.UTF_8);
        response.sendRedirect(target);
    }

    private String strAttr(Object value) {
        return Objects.isNull(value) ? null : value.toString();
    }
}
