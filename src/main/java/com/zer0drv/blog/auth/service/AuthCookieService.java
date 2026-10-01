package com.zer0drv.blog.auth.service;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 认证 Cookie 服务（blog-ui#13 契约第 1 条）：token 统一经 HttpOnly Cookie 下发/清除，
 * 不再依赖 URL query / localStorage 传递。
 * Cookie 属性：HttpOnly + Secure + Path=/ + Max-Age=token 有效期秒；
 * SameSite 默认 Strict（同站点部署，含 localhost 跨端口——SameSite 判定不含端口）；
 * 前后端跨站点部署时置 AUTH_COOKIE_SAME_SITE=None（必须 HTTPS，Secure 保持 true）。
 *
 * @author Yoruhaki
 */
@Component
public class AuthCookieService {

    @Value("${blog.auth.cookie.name:AUTH_TOKEN}")
    private String cookieName;

    @Value("${blog.auth.cookie.secure:true}")
    private boolean secure;

    @Value("${blog.auth.cookie.same-site:Strict}")
    private String sameSite;

    @Value("${jwt.expiration-ms:3600000}")
    private long expirationMs;

    public String getCookieName() {
        return cookieName;
    }

    /**
     * token 有效期（秒）：即 Cookie 的 Max-Age，也是 /auth/oauth/exchange 响应的 expiresIn
     */
    public long getExpiresInSeconds() {
        return expirationMs / 1000;
    }

    /**
     * 下发认证 Cookie：Set-Cookie: AUTH_TOKEN=<jwt>; HttpOnly; Secure; Path=/; Max-Age=<token有效期秒>
     */
    public void writeTokenCookie(HttpServletResponse response, String token) {
        ResponseCookie cookie = ResponseCookie.from(cookieName, token)
                .httpOnly(true)
                .secure(secure)
                .path("/")
                .maxAge(Duration.ofMillis(expirationMs))
                .sameSite(sameSite)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /**
     * 清除认证 Cookie（登出）：Max-Age=0
     */
    public void clearTokenCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(cookieName, "")
                .httpOnly(true)
                .secure(secure)
                .path("/")
                .maxAge(Duration.ZERO)
                .sameSite(sameSite)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
