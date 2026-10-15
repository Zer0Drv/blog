package com.zer0drv.blog.auth.service.impl;

import com.zer0drv.blog.auth.service.TokenService;
import com.zer0drv.blog.auth.event.TokenRevoked;
import com.zer0drv.blog.auth.event.UserTokensRevoked;
import com.zer0drv.blog.auth.validator.BlacklistJwtValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
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
    @org.springframework.beans.factory.annotation.Autowired
    private com.zer0drv.blog.sysconfig.service.SysConfigService sysConfig;

    private long lifetimeMs() { return sysConfig == null ? expirationMs : sysConfig.getLong("jwt.expiration-ms"); }

    /**
     * 用户活跃 token 集合键（#9）：blog:user-tokens:{userId}，成员为 jti
     */
    private static final String USER_TOKENS_KEY = "blog:user-tokens:%d";

    @Value("${jwt.expiration-ms:3600000}")
    private long expirationMs;

    private final JwtEncoder jwtEncoder;
    private final StringRedisTemplate stringRedisTemplate;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public String generateToken(Long userId, List<String> roles) {
        Instant now = Instant.now();
        long lifetime = lifetimeMs();
        String jti = UUID.randomUUID().toString().replace("-", "");
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .id(jti)
                .issuer("blog-dev")
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiresAt(now.plusMillis(lifetime))
                .claim("roles", roles)
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        // 记录 user→jti 活跃集合（#9）：封禁/解封/角色变更/改密时可整批吊销；
        // 集合 TTL 随签发滚动续期，用户长期不登录后自动清理
        String userTokensKey = USER_TOKENS_KEY.formatted(userId);
        stringRedisTemplate.opsForSet().add(userTokensKey, jti);
        // 缩短新 token 有效期时不能提前清理仍持有旧长 token 的用户索引。
        Long remaining = stringRedisTemplate.getExpire(userTokensKey);
        if (remaining == null || remaining < (lifetime + 999) / 1000)
            stringRedisTemplate.expire(userTokensKey, Duration.ofMillis(lifetime));
        return token;
    }

    @Override
    public void blackToken(String jti, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (!ttl.isNegative() && !ttl.isZero()) {
            // Spring Data Redis 4.1 起 set(K,V,long,TimeUnit) 已弃用，改用 Duration 重载
            stringRedisTemplate.opsForValue().set(BlacklistJwtValidator.BLACKLIST_PREFIX + jti, "1", ttl);
        }
        eventPublisher.publishEvent(new TokenRevoked(jti));
    }

    @Override
    public void blackUserTokens(Long userId) {
        String userTokensKey = USER_TOKENS_KEY.formatted(userId);
        Set<String> jtis = stringRedisTemplate.opsForSet().members(userTokensKey);
        if (jtis != null && !jtis.isEmpty()) {
            // 配置缩短并重启后仍须覆盖历史长 token；配置中心约束最长有效期为 7 天。
            Instant revokeUntil = Instant.now().plusMillis(sysConfig == null ? expirationMs : 604800000L);
            jtis.forEach(jti -> blackToken(jti, revokeUntil));
        }
        stringRedisTemplate.delete(userTokensKey);
        eventPublisher.publishEvent(new UserTokensRevoked(userId));
    }

    @Override
    public boolean isTokenActive(String jti, Instant expiresAt) {
        return jti != null && !jti.isBlank() && expiresAt != null && Instant.now().isBefore(expiresAt)
                && !Boolean.TRUE.equals(stringRedisTemplate.hasKey(BlacklistJwtValidator.BLACKLIST_PREFIX + jti))
                && Instant.now().isBefore(expiresAt);
    }
}
