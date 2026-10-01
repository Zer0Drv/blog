package com.zer0drv.blog.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.zer0drv.blog.auth.validator.BlacklistJwtValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * JWT 编解码配置（#2）：密钥不再内置任何默认值，未配置即启动失败（fail-fast），
 * 并强制密钥强度（HS256 至少 32 字节），防止弱密钥/公开密钥离线伪造 token。
 *
 * @author Yoruhaki
 */
@Configuration
public class JwtConfig {

    /**
     * HS256 密钥最小长度（字节）：RFC 7518 要求 HS256 密钥 ≥ 256 bit
     */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey secretKey;
    private final BlacklistJwtValidator blacklistJwtValidator;

    public JwtConfig(
            // 无默认值（#2）：缺失时解析为空串，由下方校验给出明确的启动失败提示
            @Value("${jwt.secret}") String secret,
            BlacklistJwtValidator blacklistJwtValidator
    ) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "jwt.secret 未配置，应用拒绝启动：请通过环境变量 JWT_SECRET 注入强随机密钥"
                            + "（≥32 字节，可用 openssl rand -base64 48 生成），详见 .env.example / README");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "jwt.secret 强度不足，应用拒绝启动：HS256 密钥至少 " + MIN_SECRET_BYTES
                            + " 字节（当前 " + keyBytes.length + " 字节），请更换为强随机值");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "HmacSHA256");
        this.blacklistJwtValidator = blacklistJwtValidator;
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        DelegatingOAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                blacklistJwtValidator
        );
        decoder.setJwtValidator(validator);
        return decoder;
    }
}
