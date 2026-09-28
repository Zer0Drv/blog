package com.zer0drv.blog.user.bo;

import lombok.Data;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * @author Yoruhaki
 */
@Data
public class SecurityUser implements UserDetails {

    private Long id;

    private String username;

    private String password;

    private String role;

    /**
     * 状态：0-正常；1-封禁
     */
    private Short status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @Override
    @NullMarked
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + this.role));
    }

    @Override
    public @Nullable String getPassword() {
        return this.password;
    }

    @Override
    @NullMarked
    public String getUsername() {
        return this.username;
    }

    @Override
    public boolean isEnabled() {
        return this.status == null || this.status == 0;
    }
}
