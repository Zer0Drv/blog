package com.zer0drv.blog.common.captcha;

import com.zer0drv.blog.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CaptchaServiceImpl 纯单测：阈值放行 / 达阈值缺参 / 一次性 / 错误验证码。
 *
 * @author Yoruhaki
 */
@ExtendWith(MockitoExtension.class)
class CaptchaServiceImplTest {

    private static final String SCENE = CaptchaService.SCENE_LOGIN;
    private static final String IP = "127.0.0.1";
    private static final String COUNT_KEY = "captcha:count:%s:%s".formatted(SCENE, IP);

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private CaptchaServiceImpl captchaService;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        CaptchaProperties properties = new CaptchaProperties();
        properties.setScenes(Map.of(SCENE, new CaptchaProperties.Scene(5, 600)));
        captchaService = new CaptchaServiceImpl(stringRedisTemplate, properties);
    }

    @Test
    void verify_belowThreshold_passesWithoutCaptcha() {
        // 未达阈值（2 < 5）：无验证码参数也直接放行，且不触碰验证码键
        when(valueOperations.get(COUNT_KEY)).thenReturn("2");
        assertDoesNotThrow(() -> captchaService.verify(SCENE, IP, null, null));
        verify(stringRedisTemplate, never()).delete(org.mockito.ArgumentMatchers.startsWith("captcha:login:"));
    }

    @Test
    void verify_atThreshold_missingParams_throwsCaptchaRequired() {
        // 达阈值（5 >= 5）且未带验证码：抛 CAPTCHA_REQUIRED（默认提示语）
        when(valueOperations.get(COUNT_KEY)).thenReturn("5");
        BusinessException e = assertThrows(BusinessException.class,
                () -> captchaService.verify(SCENE, IP, null, null));
        assertEquals("40066", e.getCode());
        assertEquals("操作过于频繁，请完成图形验证", e.getMessage());
    }

    @Test
    void verify_oneTimeUse_secondVerifyFails() {
        // 一次性：第一次校验通过即删，第二次（键已删）报「验证码错误或已过期」
        String codeKey = "captcha:%s:%s".formatted(SCENE, "id-1");
        when(valueOperations.get(COUNT_KEY)).thenReturn("5");
        when(valueOperations.get(codeKey)).thenReturn("ABCD", null);

        assertDoesNotThrow(() -> captchaService.verify(SCENE, IP, "id-1", "abcd"));
        verify(stringRedisTemplate).delete(codeKey);

        BusinessException e = assertThrows(BusinessException.class,
                () -> captchaService.verify(SCENE, IP, "id-1", "abcd"));
        assertEquals("40066", e.getCode());
        assertEquals("验证码错误或已过期，请重试", e.getMessage());
    }

    @Test
    void verify_wrongCode_throwsAndConsumesCode() {
        // 错误验证码：报错且一次性删除验证码键
        String codeKey = "captcha:%s:%s".formatted(SCENE, "id-2");
        when(valueOperations.get(COUNT_KEY)).thenReturn("5");
        when(valueOperations.get(codeKey)).thenReturn("ABCD");

        BusinessException e = assertThrows(BusinessException.class,
                () -> captchaService.verify(SCENE, IP, "id-2", "WXYZ"));
        assertEquals("40066", e.getCode());
        assertEquals("验证码错误或已过期，请重试", e.getMessage());
        verify(stringRedisTemplate).delete(codeKey);
    }

    @Test
    void recordAttempt_firstHit_setsWindowTtl() {
        // 首次计数：increment 返回 1 时给计数键套窗口 TTL
        when(valueOperations.increment(COUNT_KEY)).thenReturn(1L);
        captchaService.recordAttempt(SCENE, IP);
        verify(stringRedisTemplate).expire(COUNT_KEY, Duration.ofSeconds(600));
    }

    @Test
    void isRequired_atThreshold_returnsTrue() {
        when(valueOperations.get(COUNT_KEY)).thenReturn("5");
        assertTrue(captchaService.isRequired(SCENE, IP));
    }
}
