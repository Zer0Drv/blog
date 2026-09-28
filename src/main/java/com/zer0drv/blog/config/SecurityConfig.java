package com.zer0drv.blog.config;

import com.zer0drv.blog.user.enums.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 安全过滤器链（STATELESS + Bearer JWT）。
 * CSRF 关闭依据：①无会话 Cookie ②凭据经 Authorization 头显式携带 ③无 formLogin/remember-me
 * ④一旦改回 Cookie 认证必须恢复 CSRF。
 *
 * @author Yoruhaki
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        // /error 必须放行：错误转发（ERROR dispatch）也经过安全链，
                        // 不放行会把真实异常改写成 401。
                        .requestMatchers("/error", "/actuator/health").permitAll()
                        .requestMatchers("/auth/login", "/auth/register", "/auth/email-code").permitAll()
                        // 公开浏览：文章/评论/标签/分类的只读接口（M2 起逐步落地）
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/articles/**", "/comments/**", "/tags/**", "/categories/**").permitAll()
                        // 上传图片的静态访问（上传本身需登录）
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/uploads/**").permitAll()
                        .requestMatchers("/admin/**").hasRole(UserRole.ADMIN.name())
                        .anyRequest().authenticated())
                // 资源服务器：解析 Authorization: Bearer；权限映射由配置驱动
                // （authorities-claim-name=roles + authority-prefix=""，Boot 自动装配转换器）
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }

    /**
     * Spring Security 6 起 AuthenticationManager 不再自动暴露为 Bean，需显式导出。
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) {
        return configuration.getAuthenticationManager();
    }
}