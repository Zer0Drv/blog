package com.zer0drv.blog.auth.converter;

import com.zer0drv.blog.common.util.JwtSubjects;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.enums.UserRole;
import com.zer0drv.blog.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * JWT → 认证对象转换器：带 session_version 的新 token 从 roles 声明装配权限。
 * BlacklistJwtValidator 在转换前检查账号状态、持久会话版本及 Redis 吊销记录；
 * 角色变更通过受控 API 撤销会话，旧权限不能仅靠 access TTL 等待过期。
 * 无 session_version 的历史 token 保留按 sub 读库获取角色的兼容路径。
 * 每请求仍有账号状态/版本查询；这里不承诺零数据库鉴权（Refs #26）。
 *
 * @author Yoruhaki
 */
@Component
@RequiredArgsConstructor
public class DatabaseRoleJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserService userService;

    @Override
    @NullMarked
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return new JwtAuthenticationToken(jwt, currentAuthorities(jwt));
    }

    /**
     * 新 token 使用已通过版本校验的角色声明；历史 token 回退到数据库角色。
     */
    private List<GrantedAuthority> currentAuthorities(Jwt jwt) {
        // Versioned claims are guarded by the shared database session epoch validator.
        // Legacy tokens retain the database-role compatibility path until they expire.
        if (jwt.getClaim("session_version") != null) {
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles == null) return List.of();
            var authorities = new java.util.ArrayList<GrantedAuthority>();
            for (String role : roles) {
                if (role.startsWith("ROLE_") && UserRole.isValid(role.substring(5))) authorities.add(new SimpleGrantedAuthority(role));
            }
            if (roles.contains("ROLE_SUPER_ADMIN")) authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
            return authorities;
        }
        Long userId = JwtSubjects.userIdOf(jwt);
        User user = userService.getById(userId);
        if (Objects.isNull(user) || !UserRole.isValid(user.getRole())) {
            return List.of();
        }
        if (UserRole.SUPER_ADMIN.name().equals(user.getRole()))
            return List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"), new SimpleGrantedAuthority("ROLE_ADMIN"));
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()));
    }
}
