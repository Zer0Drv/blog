package com.zer0drv.blog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 从 SecurityConfig 拆出的安全支撑 Bean（PasswordEncoder / AuthenticationManager）。
 * 独立成类的原因：这些 Bean 若定义在 SecurityConfig 内，
 * SecurityConfig（构造器注入 OAuth2LoginSuccessHandler）→ OAuth2LoginSuccessHandler
 * → AuthServiceImpl（构造器注入 PasswordEncoder / AuthenticationManager）→ SecurityConfig
 * 会形成构造器循环依赖；@Configuration 类构造器注入无法被 Spring 提前暴露，
 * allow-circular-references=true 也救不了。拆到独立配置类后这些 Bean 可先实例化，
 * 循环链路随之断开。
 *
 * @author Yoruhaki
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Spring Security 6 起 AuthenticationManager 不再自动暴露为 Bean，需显式导出。
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) {
        return configuration.getAuthenticationManager();
    }
}
