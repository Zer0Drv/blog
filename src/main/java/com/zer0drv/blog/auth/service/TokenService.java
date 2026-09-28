package com.zer0drv.blog.auth.service;

import java.time.Instant;
import java.util.List;

/**
 * @author Yoruhaki
 */
public interface TokenService {

    /**
     * 生成 jwt
     *
     * @param userId 用户id
     * @param roles  角色列表（形如 ROLE_USER）
     * @return jwt
     */
    String generateToken(Long userId, List<String> roles);

    /**
     * 拉黑 jwt（登出/改密后旧 token 作废）
     *
     * @param jti       凭据 id
     * @param expiresAt 过期时间
     */
    void blackToken(String jti, Instant expiresAt);
}
