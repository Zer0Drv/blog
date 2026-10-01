package com.zer0drv.blog.auth;

import com.zer0drv.blog.auth.service.EmailCodeService;
import com.zer0drv.blog.auth.service.impl.EmailCodeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EmailCodeServiceImpl 纯单测（#3）：校验失败计数、5 次作废、成功消费清零。
 *
 * @author Yoruhaki
 */
@ExtendWith(MockitoExtension.class)
class EmailCodeServiceImplTest {

    private static final String SCENE = EmailCodeService.SCENE_RESET;
    private static final String EMAIL = "bob@example.com";
    private static final String CODE_KEY = "blog:email-code:" + SCENE + ":" + EMAIL;
    private static final String FAIL_KEY = "blog:email-code:fail:" + SCENE + ":" + EMAIL;

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private ObjectProvider<JavaMailSender> mailSenderProvider;

    private EmailCodeServiceImpl emailCodeService;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        emailCodeService = new EmailCodeServiceImpl(stringRedisTemplate, mailSenderProvider);
    }

    @Test
    void verify_correctCode_consumesAndClearsFailCounter() {
        when(valueOperations.get(CODE_KEY)).thenReturn("123456");

        assertTrue(emailCodeService.verify(SCENE, EMAIL, "123456"));

        verify(stringRedisTemplate).delete(CODE_KEY);
        verify(stringRedisTemplate).delete(FAIL_KEY);
        verify(valueOperations, never()).increment(anyString());
    }

    @Test
    void verify_wrongCode_incrementsFailCounterWithTtl() {
        when(valueOperations.get(CODE_KEY)).thenReturn("123456");
        when(valueOperations.increment(FAIL_KEY)).thenReturn(1L);

        assertFalse(emailCodeService.verify(SCENE, EMAIL, "000000"));

        verify(valueOperations).increment(FAIL_KEY);
        // 首次失败套上与验证码相同的 TTL
        verify(stringRedisTemplate).expire(eq(FAIL_KEY), any(Duration.class));
        // 未达上限不作废验证码
        verify(stringRedisTemplate, never()).delete(CODE_KEY);
    }

    @Test
    void verify_fifthFailure_invalidatesCode() {
        when(valueOperations.get(CODE_KEY)).thenReturn("123456");
        when(valueOperations.increment(FAIL_KEY)).thenReturn(5L);

        assertFalse(emailCodeService.verify(SCENE, EMAIL, "000000"));

        // 连续 5 次失败：验证码作废 + 失败计数清零，必须重新发送
        verify(stringRedisTemplate).delete(CODE_KEY);
        verify(stringRedisTemplate).delete(FAIL_KEY);
    }

    @Test
    void verify_noStoredCode_countsAsFailure() {
        when(valueOperations.get(CODE_KEY)).thenReturn(null);
        when(valueOperations.increment(FAIL_KEY)).thenReturn(2L);

        assertFalse(emailCodeService.verify(SCENE, EMAIL, "123456"));

        verify(valueOperations).increment(FAIL_KEY);
        verify(stringRedisTemplate, never()).delete(CODE_KEY);
    }
}
