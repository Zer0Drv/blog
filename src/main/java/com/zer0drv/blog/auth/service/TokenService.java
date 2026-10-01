package com.zer0drv.blog.auth.service;

import java.time.Instant;
import java.util.List;

/**
 * @author Yoruhaki
 */
public interface TokenService {

    /**
     * 生成 jwt（同时记录 user→jti 活跃集合，供整批吊销）
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

    /**
     * 吊销某用户全部活跃 token（#9）：封禁/解封/角色变更/改密/重置密码时调用。
     * 颁发时已按 user→jti 集合（Redis Set）记录，此处整批写入黑名单使历史 token 立即失效。
     *
     * @param userId 用户id
     */
    void blackUserTokens(Long userId);
}
