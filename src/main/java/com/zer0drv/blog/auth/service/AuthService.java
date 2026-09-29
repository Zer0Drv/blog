package com.zer0drv.blog.auth.service;

import com.zer0drv.blog.auth.dto.ChangePasswordDTO;
import com.zer0drv.blog.auth.dto.PasswordResetDTO;
import com.zer0drv.blog.auth.dto.RegisterDTO;
import com.zer0drv.blog.auth.dto.UserLoginDTO;
import com.zer0drv.blog.user.domain.User;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Map;

/**
 * @author Yoruhaki
 */
public interface AuthService {

    /**
     * 账号密码登录
     *
     * @param dto 用户凭据
     * @return access_token / token_type
     */
    Map<String, String> userLogin(UserLoginDTO dto);

    /**
     * 邮箱验证码注册（注册成功直接签发 token 完成登录）
     *
     * @param dto 注册信息
     * @return access_token / token_type
     */
    Map<String, String> register(RegisterDTO dto);

    /**
     * 登出（拉黑当前 token）
     */
    String userLogout(Jwt jwt);

    /**
     * 当前登录用户信息
     */
    User getProfile(Jwt jwt);

    /**
     * 修改密码（旧 token 作废，需重新登录）
     */
    void changePassword(Jwt jwt, ChangePasswordDTO dto);

    /**
     * 找回密码：发送验证码（邮箱未注册时报 EMAIL_NOT_REGISTERED，不向未注册邮箱发码）
     *
     * @param email 注册邮箱
     */
    void sendPasswordResetCode(String email);

    /**
     * 找回密码：验证码校验通过后以 BCrypt 重置密码。
     * 注：历史已签发 token 不作废（README 已声明的 JWT 取舍：黑名单仅覆盖登出/改密场景），到期自然失效。
     * OAuth 占位账号（password=''）通过本流程设置密码后即开通密码登录——预期行为。
     *
     * @param dto email + code + newPassword
     */
    void resetPassword(PasswordResetDTO dto);

    /**
     * GitHub OAuth 登录：已绑定用户直接签发 token；未绑定则自动注册（用户名 gh_+login 兜底去重，
     * 邮箱为空时用 gh_{githubId}@oauth.local 占位以满足 uk_email 唯一约束）。
     *
     * @param githubId  GitHub 用户 id（attributes.id）
     * @param login     GitHub 登录名
     * @param name      显示名（可为空）
     * @param avatarUrl 头像 URL（可为空）
     * @param email     邮箱（GitHub 用户隐藏邮箱时为 null）
     * @return access_token / token_type
     */
    Map<String, String> loginByGithub(Long githubId, String login, String name, String avatarUrl, String email);
}
