package com.zer0drv.blog.common.util;

import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.StatusCode;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 从 JWT 的 sub 声明解析当前用户 id（签发时 sub = 用户 id 字符串）。
 *
 * @author Yoruhaki
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class JwtSubjects {

    public static Long userIdOf(Jwt jwt) {
        String subject = (jwt == null) ? null : jwt.getSubject();
        if (subject == null) {
            throw new BusinessException(StatusCode.LOGIN_STATUS_INVALID);
        }
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException _) {
            throw new BusinessException(StatusCode.LOGIN_STATUS_INVALID);
        }
    }
}
