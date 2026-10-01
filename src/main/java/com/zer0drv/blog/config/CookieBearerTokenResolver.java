package com.zer0drv.blog.config;

import com.zer0drv.blog.auth.service.AuthCookieService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.stereotype.Component;

/**
 * JWT 读取（blog-ui#13 契约第 3 条）：资源服务器优先从 HttpOnly Cookie（AUTH_TOKEN）取 token，
 * 兼容 Authorization: Bearer 头（过渡期保留，供 CLI / 旧客户端 / 集成测试使用）。
 *
 * @author Yoruhaki
 */
@Component
@RequiredArgsConstructor
public class CookieBearerTokenResolver implements BearerTokenResolver {

    private final AuthCookieService authCookieService;

    /**
     * 过渡期兜底：Authorization: Bearer 头（DefaultBearerTokenResolver 默认不接受 query 参数）
     */
    private final DefaultBearerTokenResolver headerDelegate = new DefaultBearerTokenResolver();

    @Override
    public String resolve(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (authCookieService.getCookieName().equals(cookie.getName())
                        && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                    return cookie.getValue();
                }
            }
        }
        return headerDelegate.resolve(request);
    }
}
