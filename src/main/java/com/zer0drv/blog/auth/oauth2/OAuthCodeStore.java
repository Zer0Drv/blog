package com.zer0drv.blog.auth.oauth2;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * OAuth 一次性换码存取（blog-ui#13 契约第 2 条）：OAuth 登录成功后把 token 暂存 Redis
 * （oauth:code:<code> → token，TTL 60 秒），前端回调页拿 code 调 POST /auth/oauth/exchange
 * 换取 HttpOnly Cookie；消费即删，一次性，防止 token 经 URL 泄露（浏览器历史/日志/Referer）。
 *
 * @author Yoruhaki
 */
@Component
@RequiredArgsConstructor
public class OAuthCodeStore {

    private static final String CODE_KEY = "oauth:code:%s";
    private static final Duration CODE_TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 暂存 token，返回一次性 code
     */
    public String store(String token) {
        String code = UUID.randomUUID().toString().replace("-", "");
        stringRedisTemplate.opsForValue().set(CODE_KEY.formatted(code), token, CODE_TTL);
        return code;
    }

    /**
     * 消费一次性 code（取出即删）；不存在/已过期/已消费返回 null
     */
    public String consume(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return stringRedisTemplate.opsForValue().getAndDelete(CODE_KEY.formatted(code));
    }
}
