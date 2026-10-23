package com.zer0drv.blog.auth.service;

import com.zer0drv.blog.auth.event.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class SessionRevocationBroadcastTest {
    @Test void remoteDeliveryDoesNotRepeatVersionMutationAndOwnMessagesAreIgnored() {
        var redis = mock(StringRedisTemplate.class);
        var events = mock(ApplicationEventPublisher.class);
        var bridge = new SessionRevocationBroadcast(redis, events);
        bridge.user(new UserTokensRevoked(42L));
        var payload = ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(eq(SessionRevocationBroadcast.CHANNEL), payload.capture());
        bridge.receive(payload.getValue());
        verifyNoInteractions(events);
        bridge.receive("another-node:user:42");
        verify(events).publishEvent(new RemoteSessionRevocation(42L,null));
        bridge.receive("another-node:token:" + "a".repeat(32));
        verify(events).publishEvent(new RemoteSessionRevocation(null,"a".repeat(32)));
        bridge.receive("another-node:user:-1");
        bridge.receive("invalid");
        verifyNoMoreInteractions(events);
    }
}
