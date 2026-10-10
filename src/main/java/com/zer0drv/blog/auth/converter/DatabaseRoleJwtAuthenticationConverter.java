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
 * JWT → 认证对象转换器：权限不取 token 内的 roles 声明，而是按 sub（用户 id）实时读库装配。
 * 背景：roles 声明在登录签发时固化，此后角色变更（尤其是库内直改提拔 ADMIN——管理接口
 * 本身不允许把用户设为 ADMIN）不会反映到已签发 token，持旧 token 访问 /admin/** 会误返 403，
 * 反向降级则误放行。改为实时读库后角色变更即时生效，无需重新登录；
 * token 吊销（封禁/改密/登出）仍由 BlacklistJwtValidator 负责，两者正交。
 * 代价：每个携带 token 的请求多一次 user 主键查询（博客规模可忽略）。
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
     * 按 sub 实时读库装配当前角色；用户已删除或角色值非法时无权限（等效降级为仅登录）
     */
    private List<GrantedAuthority> currentAuthorities(Jwt jwt) {
        Long userId = JwtSubjects.userIdOf(jwt);
        User user = userService.getById(userId);
        if (Objects.isNull(user) || !UserRole.isValid(user.getRole())) {
            return List.of();
        }
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()));
    }
}
