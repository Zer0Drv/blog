package com.zer0drv.blog.auth.service.impl;

import com.zer0drv.blog.auth.service.TokenService;
import com.zer0drv.blog.auth.validator.BlacklistJwtValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * @author Yoruhaki
 */
@Service
@RequiredArgsConstructor
public class TokenServiceImpl implements TokenService {

    /**
     * 用户活跃 token 集合键（#9）：blog:user-tokens:{userId}，成员为 jti
     */
    private static final String USER_TOKENS_KEY = "blog:user-tokens:%d";

    @Value("${jwt.expiration-ms:3600000}")
    private long expirationMs;

    private final JwtEncoder jwtEncoder;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public String generateToken(Long userId, List<String> roles) {
        Instant now = Instant.now();
        String jti = UUID.randomUUID().toString().replace("-", "");
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .id(jti)
                .issuer("blog-dev")
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiresAt(now.plusMillis(expirationMs))
                .claim("roles", roles)
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        // 记录 user→jti 活跃集合（#9）：封禁/解封/角色变更/改密时可整批吊销；
        // 集合 TTL 随签发滚动续期，用户长期不登录后自动清理
        String userTokensKey = USER_TOKENS_KEY.formatted(userId);
        stringRedisTemplate.opsForSet().add(userTokensKey, jti);
        stringRedisTemplate.expire(userTokensKey, Duration.ofMillis(expirationMs));
        return token;
    }

    @Override
    public void blackToken(String jti, Instant expiresAt) {
        long ttl = Duration.between(Instant.now(), expiresAt).getSeconds();
        if (ttl > 0) {
            // Spring Data Redis 4.1 起 set(K,V,long,TimeUnit) 已弃用，改用 Duration 重载
            stringRedisTemplate.opsForValue().set(BlacklistJwtValidator.BLACKLIST_PREFIX + jti, "1", Duration.ofSeconds(ttl));
        }
    }

    @Override
    public void blackUserTokens(Long userId) {
        String userTokensKey = USER_TOKENS_KEY.formatted(userId);
        Set<String> jtis = stringRedisTemplate.opsForSet().members(userTokensKey);
        if (jtis != null && !jtis.isEmpty()) {
            // 黑名单 TTL 取完整签发有效期（个别 token 会略超其实际过期时间，到期自清，无副作用）
            Instant revokeUntil = Instant.now().plusMillis(expirationMs);
            jtis.forEach(jti -> blackToken(jti, revokeUntil));
        }
        stringRedisTemplate.delete(userTokensKey);
    }
}
