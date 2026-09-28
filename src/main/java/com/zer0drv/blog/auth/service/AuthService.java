package com.zer0drv.blog.auth.service;

import com.zer0drv.blog.auth.dto.ChangePasswordDTO;
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
}
