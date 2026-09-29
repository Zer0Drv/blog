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

/**
 * 安全过滤器链（STATELESS + Bearer JWT）。
 * CSRF 关闭依据：①无会话 Cookie ②凭据经 Authorization 头显式携带 ③无 formLogin/remember-me
 * ④一旦改回 Cookie 认证必须恢复 CSRF。
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

    /**
     * OAuth 登录失败回跳地址
     */
    @org.springframework.beans.factory.annotation.Value(
            "${blog.oauth.failure-redirect:http://localhost:5173/login?error=oauth_failed}")
    private String oauthFailureRedirect;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) {
        http.csrf(csrf -> csrf.disable())
                // IF_REQUIRED：仅 GitHub oauth2Login 授权码流程需要暂存授权请求（state 参数自带防 CSRF），
                // 对 Bearer API 的调用方行为无影响（不主动创建会话）
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(authorize -> authorize
                        // /error 必须放行：错误转发（ERROR dispatch）也经过安全链，
                        // 不放行会把真实异常改写成 401。
                        .requestMatchers("/error", "/actuator/health").permitAll()
                        .requestMatchers("/auth/login", "/auth/register", "/auth/email-code",
                                "/auth/password-reset-code", "/auth/password-reset").permitAll()
                        // 图形验证码端点（频率触发，匿名可获取/预检）
                        .requestMatchers("/auth/captcha", "/auth/captcha/required").permitAll()
                        // GitHub OAuth2 登录端点与回调
                        .requestMatchers("/oauth2/authorization/**", "/login/oauth2/code/**").permitAll()
                        .requestMatchers("/ws").permitAll()
                        // 公开浏览：文章/评论/标签/分类/用户主页（粉丝/关注/profile/文章）的只读接口
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/articles/**", "/comments/**", "/tags/**", "/categories/**",
                                "/users/**").permitAll()
                        // 上传图片的静态访问（上传本身需登录）
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/uploads/**").permitAll()
                        .requestMatchers("/admin/**").hasRole(UserRole.ADMIN.name())
                        .anyRequest().authenticated())
                // GitHub OAuth2 登录：成功后由 successHandler 签发本站 JWT 并 302 回前端
                .oauth2Login(oauth2 -> oauth2
                        .successHandler(oAuth2LoginSuccessHandler)
                        .failureHandler((request, response, exception) ->
                                response.sendRedirect(oauthFailureRedirect)))
                // 资源服务器：解析 Authorization: Bearer；权限映射由配置驱动
                // （authorities-claim-name=roles + authority-prefix=""，Boot 自动装配转换器）
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }
}