package com.zer0drv.blog.common.captcha;

import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.StatusCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CaptchaGuardImpl 纯单测（CaptchaService 以 Mock 顶替，只验编排、不验原子能力）：
 * 简单路径 verify→record 顺序与失败短路；
 * 登录路径双维度任一达阈值触发强校验、成功清零两维、失败计数两维且原异常原样抛出、
 * 未达阈值不校验直接放行 action。
 *
 * @author Yoruhaki
 */
@ExtendWith(MockitoExtension.class)
class CaptchaGuardImplTest {

    private static final String SCENE = CaptchaService.SCENE_LOGIN;
    private static final String IP = "127.0.0.1";
    private static final String USER_KEY = "u:alice";
    private static final String CAPTCHA_ID = "id-1";
    private static final String CAPTCHA_CODE = "abcd";

    @Mock
    private CaptchaService captchaService;

    private CaptchaGuardImpl captchaGuard;

    @BeforeEach
    void setUp() {
        captchaGuard = new CaptchaGuardImpl(captchaService);
    }

    // ===== 简单路径：verifyAndRecord =====

    @Test
    void verifyAndRecord_verifyPasses_recordsAttemptAfterVerify() {
        captchaGuard.verifyAndRecord(SCENE, IP, CAPTCHA_ID, CAPTCHA_CODE);

        InOrder inOrder = inOrder(captchaService);
        inOrder.verify(captchaService).verify(SCENE, IP, CAPTCHA_ID, CAPTCHA_CODE);
        inOrder.verify(captchaService).recordAttempt(SCENE, IP);
    }

    @Test
    void verifyAndRecord_verifyFails_shortCircuitsAndDoesNotRecord() {
        // 校验失败（缺参 / 错误或过期）：原异常原样抛出，本次尝试不计数
        BusinessException boom = new BusinessException(StatusCode.CAPTCHA_REQUIRED);
        doThrow(boom).when(captchaService).verify(SCENE, IP, null, null);

        BusinessException thrown = assertThrows(BusinessException.class,
                () -> captchaGuard.verifyAndRecord(SCENE, IP, null, null));

        assertSame(boom, thrown);
        verify(captchaService, never()).recordAttempt(any(), any());
    }

    // ===== 登录路径：guarded 阈值判定 =====

    @Test
    void guarded_belowThreshold_skipsVerifyAndRunsActionDirectly() {
        // 两维均未达阈值：不触碰验证码，直接放行 action，成功后清零两维
        when(captchaService.isRequired(SCENE, IP)).thenReturn(false);
        when(captchaService.isRequired(SCENE, USER_KEY)).thenReturn(false);

        String result = captchaGuard.guarded(SCENE, IP, USER_KEY, null, null, () -> "tokens");

        assertEquals("tokens", result);
        verify(captchaService, never()).verifyForced(any(), any(), any());
        verify(captchaService, never()).recordAttempt(any(), any());
    }

    @Test
    void guarded_ipAtThreshold_forcesVerify() {
        when(captchaService.isRequired(SCENE, IP)).thenReturn(true);

        captchaGuard.guarded(SCENE, IP, USER_KEY, CAPTCHA_ID, CAPTCHA_CODE, () -> "tokens");

        // IP 维度命中即短路，不再查用户名维度
        verify(captchaService, never()).isRequired(SCENE, USER_KEY);
        verify(captchaService).verifyForced(SCENE, CAPTCHA_ID, CAPTCHA_CODE);
    }

    @Test
    void guarded_userKeyAtThreshold_forcesVerify() {
        when(captchaService.isRequired(SCENE, IP)).thenReturn(false);
        when(captchaService.isRequired(SCENE, USER_KEY)).thenReturn(true);

        captchaGuard.guarded(SCENE, IP, USER_KEY, CAPTCHA_ID, CAPTCHA_CODE, () -> "tokens");

        verify(captchaService).verifyForced(SCENE, CAPTCHA_ID, CAPTCHA_CODE);
    }

    // ===== 登录路径：guarded 成功 =====

    @Test
    void guarded_success_clearsBothDimensionsAfterAction() {
        when(captchaService.isRequired(SCENE, IP)).thenReturn(true);
        @SuppressWarnings("unchecked")
        Supplier<String> action = mock(Supplier.class);
        when(action.get()).thenReturn("tokens");

        String result = captchaGuard.guarded(SCENE, IP, USER_KEY, CAPTCHA_ID, CAPTCHA_CODE, action);

        assertEquals("tokens", result);
        // 顺序：强校验 → action → 清零 IP 维 → 清零用户名维
        InOrder inOrder = inOrder(captchaService, action);
        inOrder.verify(captchaService).verifyForced(SCENE, CAPTCHA_ID, CAPTCHA_CODE);
        inOrder.verify(action).get();
        inOrder.verify(captchaService).clearAttempts(SCENE, IP);
        inOrder.verify(captchaService).clearAttempts(SCENE, USER_KEY);
        verify(captchaService, never()).recordAttempt(any(), any());
    }

    // ===== 登录路径：guarded 失败 =====

    @Test
    void guarded_actionFailsWithBusinessException_recordsBothDimensionsAndRethrows() {
        when(captchaService.isRequired(SCENE, IP)).thenReturn(false);
        when(captchaService.isRequired(SCENE, USER_KEY)).thenReturn(false);
        BusinessException boom = new BusinessException(StatusCode.USER_PASSWORD_ERROR);
        Supplier<Object> action = () -> {
            throw boom;
        };

        BusinessException thrown = assertThrows(BusinessException.class,
                () -> captchaGuard.guarded(SCENE, IP, USER_KEY, null, null, action));

        // 原异常实例原样抛出；两维各 +1（IP 维先于用户名维）；不清零
        assertSame(boom, thrown);
        InOrder inOrder = inOrder(captchaService);
        inOrder.verify(captchaService).recordAttempt(SCENE, IP);
        inOrder.verify(captchaService).recordAttempt(SCENE, USER_KEY);
        verify(captchaService, never()).clearAttempts(any(), any());
    }

    @Test
    void guarded_forcedVerifyFails_recordsBothDimensionsAndSkipsAction() {
        // 达阈值但验证码错误：action 不执行，失败照样双维度计数，原异常原样抛出
        when(captchaService.isRequired(SCENE, IP)).thenReturn(true);
        BusinessException boom = new BusinessException(StatusCode.CAPTCHA_REQUIRED.getCode(), "验证码错误或已过期，请重试");
        doThrow(boom).when(captchaService).verifyForced(SCENE, CAPTCHA_ID, CAPTCHA_CODE);
        @SuppressWarnings("unchecked")
        Supplier<Object> action = mock(Supplier.class);

        BusinessException thrown = assertThrows(BusinessException.class,
                () -> captchaGuard.guarded(SCENE, IP, USER_KEY, CAPTCHA_ID, CAPTCHA_CODE, action));

        assertSame(boom, thrown);
        verify(action, never()).get();
        InOrder inOrder = inOrder(captchaService);
        inOrder.verify(captchaService).recordAttempt(SCENE, IP);
        inOrder.verify(captchaService).recordAttempt(SCENE, USER_KEY);
        verify(captchaService, never()).clearAttempts(any(), any());
    }

    @Test
    void guarded_actionFailsWithNonBusinessException_propagatesWithoutCounting() {
        // 非 BusinessException（如底层故障）：不计数、不清零，原样抛出
        when(captchaService.isRequired(SCENE, IP)).thenReturn(false);
        when(captchaService.isRequired(SCENE, USER_KEY)).thenReturn(false);
        RuntimeException boom = new IllegalStateException("db down");
        Supplier<Object> action = () -> {
            throw boom;
        };

        RuntimeException thrown = assertThrows(IllegalStateException.class,
                () -> captchaGuard.guarded(SCENE, IP, USER_KEY, null, null, action));

        assertSame(boom, thrown);
        verify(captchaService, never()).recordAttempt(any(), any());
        verify(captchaService, never()).clearAttempts(any(), any());
    }
}
