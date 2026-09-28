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
import java.util.UUID;

/**
 * @author Yoruhaki
 */
@Service
@RequiredArgsConstructor
public class TokenServiceImpl implements TokenService {

    @Value("${jwt.expiration-ms:3600000}")
    private long expirationMs;

    private final JwtEncoder jwtEncoder;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public String generateToken(Long userId, List<String> roles) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .issuer("blog-dev")
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiresAt(now.plusMillis(expirationMs))
                .claim("roles", roles)
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    @Override
    public void blackToken(String jti, Instant expiresAt) {
        long ttl = Duration.between(Instant.now(), expiresAt).getSeconds();
        if (ttl > 0) {
            // Spring Data Redis 4.1 起 set(K,V,long,TimeUnit) 已弃用，改用 Duration 重载
            stringRedisTemplate.opsForValue().set(BlacklistJwtValidator.BLACKLIST_PREFIX + jti, "1", Duration.ofSeconds(ttl));
        }
    }
}
