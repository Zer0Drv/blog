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
 * @author Yoruhaki
 */
@Configuration
public class JwtConfig {

    private final SecretKey secretKey;
    private final BlacklistJwtValidator blacklistJwtValidator;

    public JwtConfig(
            @Value("${jwt.secret:my-very-long-and-secure-secret-key-32bytes-minimum}") String secret,
            BlacklistJwtValidator blacklistJwtValidator
    ) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
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
