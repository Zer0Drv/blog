package com.zer0drv.blog.config;

import com.zer0drv.blog.user.enums.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * 安全过滤器链（STATELESS + JWT）。
 * 认证承载（blog-ui#13）：优先 HttpOnly Cookie（AUTH_TOKEN），兼容 Authorization: Bearer（过渡期）。
 * CSRF 说明：本项目无 Cookie 会话，仍关闭 Spring Security 的会话型 CSRF；Cookie 认证引入的
 * CSRF 面由 SameSite=Strict Cookie + CsrfHeaderCheckFilter（mutating 请求强制自定义头）纵深覆盖。
 * 注意：PasswordEncoder / AuthenticationManager 定义在 PasswordEncoderConfig
 * （独立成类以避免与本类形成构造器循环依赖）。
 *
 * @author Yoruhaki
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final com.zer0drv.blog.auth.oauth2.OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;
    private final CookieBearerTokenResolver cookieBearerTokenResolver;

    /**
     * OAuth 登录失败回跳地址
     */
    @org.springframework.beans.factory.annotation.Value(
            "${blog.oauth.failure-redirect:http://localhost:5173/login?error=oauth_failed}")
    private String oauthFailureRedirect;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) {
        http.csrf(csrf -> csrf.disable())
                // 安全响应头（#6-1）：CSP 默认仅 self（img-src 额外放行 http/https/data：
                // 文章图床可走独立 MinIO 域名）；nosniff / frame DENY / no-referrer / HSTS（仅 HTTPS 请求生效）
                .headers(headers -> headers
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.deny())
                        .httpStrictTransportSecurity(Customizer.withDefaults())
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; img-src 'self' data: http: https:;"
                                        + " style-src 'self' 'unsafe-inline'")))
                // IF_REQUIRED：仅 GitHub oauth2Login 授权码流程需要暂存授权请求（state 参数自带防 CSRF），
                // 对 Bearer/Cookie API 的调用方行为无影响（不主动创建会话）
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(authorize -> authorize
                        // /error 必须放行：错误转发（ERROR dispatch）也经过安全链，
                        // 不放行会把真实异常改写成 401。
                        .requestMatchers("/error", "/actuator/health").permitAll()
                        .requestMatchers("/auth/login", "/auth/register", "/auth/email-code",
                                "/auth/password-reset-code", "/auth/password-reset",
                                "/auth/oauth/exchange").permitAll()
                        // 图形验证码端点（频率触发，匿名可获取/预检）
                        .requestMatchers("/auth/captcha", "/auth/captcha/required").permitAll()
                        // GitHub OAuth2 登录端点与回调
                        .requestMatchers("/oauth2/authorization/**", "/login/oauth2/code/**").permitAll()
                        .requestMatchers("/ws").permitAll()
                        // 公开浏览：文章/评论/标签/分类的只读接口
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/articles/**", "/comments/**", "/tags/**", "/categories/**").permitAll()
                        // 用户公开主页（#6-5：不再整段放行 /users/**，仅放行确需匿名的 4 条；
                        // /users/me/** 等敏感 GET 由 anyRequest 兜底认证，后续新增敏感 GET 同样兜底）
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/users/*/followers", "/users/*/following",
                                "/users/*/profile", "/users/*/articles").permitAll()
                        // 上传图片的静态访问（上传本身需登录）
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/uploads/**").permitAll()
                        // P0：公开站点配置（白名单键）与 SEO 三件套（RSS/Atom/sitemap/robots）
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/site/config", "/rss.xml", "/atom.xml", "/sitemap.xml", "/robots.txt").permitAll()
                        .requestMatchers("/admin/**").hasRole(UserRole.ADMIN.name())
                        .anyRequest().authenticated())
                // GitHub OAuth2 登录：成功后由 successHandler 签发一次性换码 code 并 302 回前端
                .oauth2Login(oauth2 -> oauth2
                        .successHandler(oAuth2LoginSuccessHandler)
                        .failureHandler((request, response, exception) ->
                                response.sendRedirect(oauthFailureRedirect)))
                // 资源服务器：优先 Cookie 取 token，兼容 Authorization: Bearer（过渡期）；
                // 权限映射由配置驱动（authorities-claim-name=roles + authority-prefix=""，Boot 自动装配转换器）
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(cookieBearerTokenResolver)
                        .jwt(Customizer.withDefaults()));
        return http.build();
    }
}
