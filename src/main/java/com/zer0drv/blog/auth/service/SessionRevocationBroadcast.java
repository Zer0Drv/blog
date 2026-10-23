package com.zer0drv.blog.auth.service;

import com.zer0drv.blog.auth.event.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.transaction.event.TransactionalEventListener;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Shared Redis/SQL provide durable revocation; Pub/Sub promptly closes sockets on other nodes. */
@Configuration
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name="blog.auth.revocation-broadcast-enabled", matchIfMissing=true)
public class SessionRevocationBroadcast {
    static final String CHANNEL = "blog:session-revoked:v1";
    private final StringRedisTemplate redis;
    private final ApplicationEventPublisher events;
    private final String instance = UUID.randomUUID().toString();

    @TransactionalEventListener(fallbackExecution=true)
    public void user(UserTokensRevoked event) { publish("user", String.valueOf(event.userId())); }
    @TransactionalEventListener(fallbackExecution=true)
    public void token(TokenRevoked event) { publish("token", event.jti()); }
    private void publish(String type, String id) {
        try { redis.convertAndSend(CHANNEL, instance + ":" + type + ":" + id); }
        catch (Exception unavailable) { log.warn("会话撤销广播暂不可用，共享黑名单及连接定期校验继续生效"); }
    }
    void receive(String message) {
        String[] fields = message.split(":", -1);
        if (fields.length != 3 || instance.equals(fields[0])) return;
        if ("user".equals(fields[1]) && fields[2].matches("[1-9][0-9]{0,17}"))
            events.publishEvent(new RemoteSessionRevocation(Long.parseLong(fields[2]), null));
        else if ("token".equals(fields[1]) && fields[2].matches("[a-f0-9]{32}"))
            events.publishEvent(new RemoteSessionRevocation(null, fields[2]));
    }
    @Bean
    RedisMessageListenerContainer sessionRevocationListener(RedisConnectionFactory connection) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connection);
        container.addMessageListener((message, pattern) -> receive(new String(message.getBody(), StandardCharsets.UTF_8)), new ChannelTopic(CHANNEL));
        return container;
    }
}
