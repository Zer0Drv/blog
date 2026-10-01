package com.zer0drv.blog.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * CSRF 纵深防御（blog-ui#13 契约第 6 条）：Cookie 认证改造后浏览器会自动携带 AUTH_TOKEN，
 * 跨站表单/fetch 可借用 Cookie 发起请求。SameSite Cookie 是第一道防线，本过滤器为第二道：
 * 所有 mutating 请求（POST/PUT/DELETE/PATCH，认证入口除外）必须携带自定义头
 * X-Requested-With: XMLHttpRequest（跨站表单无法携带自定义头），缺失一律 403。
 * 注册顺序：@Order(LOWEST_PRECEDENCE) 排在安全链之后（未认证请求仍先由安全链 401/403）。
 *
 * @author Yoruhaki
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class CsrfHeaderCheckFilter extends OncePerRequestFilter {

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    /**
     * 认证入口豁免（此时 Cookie 尚未建立，登录/注册/换码本身无会话状态可被盗用）
     */
    private static final Set<String> EXEMPT_PATHS = Set.of(
            "/auth/login", "/auth/register", "/auth/oauth/exchange");

    private static final String REQUIRED_HEADER = "X-Requested-With";
    private static final String REQUIRED_HEADER_VALUE = "XMLHttpRequest";

    private final boolean enabled;

    public CsrfHeaderCheckFilter(
            @Value("${blog.security.csrf-header-check-enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (!enabled || !MUTATING_METHODS.contains(request.getMethod())
                || EXEMPT_PATHS.contains(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        String header = request.getHeader(REQUIRED_HEADER);
        if (header != null && REQUIRED_HEADER_VALUE.equalsIgnoreCase(header.trim())) {
            filterChain.doFilter(request, response);
            return;
        }
        log.warn("拒绝缺少 {} 头的 {} 请求：{}", REQUIRED_HEADER, request.getMethod(), request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":\"40300\",\"message\":\"缺少防 CSRF 请求头（X-Requested-With: XMLHttpRequest）\",\"data\":null}");
    }
}
