package com.zer0drv.blog.auth.validator;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * @author Yoruhaki
 */
@Component
@RequiredArgsConstructor
public class BlacklistJwtValidator implements OAuth2TokenValidator<Jwt> {

    public static final String BLACKLIST_PREFIX = "blog:blacklist:jwt:";

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    @NullMarked
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String jti = token.getId();
        Boolean isBlacked = stringRedisTemplate.hasKey(BLACKLIST_PREFIX + jti);
        if (Boolean.TRUE.equals(isBlacked)) {
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Token has been revoked", null));
        }
        return OAuth2TokenValidatorResult.success();
    }
}
