package com.zer0drv.blog.common.captcha;

import com.zer0drv.blog.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * {@link CaptchaGuard} 默认实现：只编排 {@link CaptchaService} 原子能力，自身无状态。
 * 编排语义与收编前各端点内的手写三段式逐字节等价。
 *
 * @author Yoruhaki
 */
@Service
@RequiredArgsConstructor
public class CaptchaGuardImpl implements CaptchaGuard {

    private final CaptchaService captchaService;

    @Override
    public void verifyAndRecord(String scene, String sourceKey, String captchaId, String captchaCode) {
        // verify 失败（缺参 / 错误或过期）短路抛出，本次尝试不计数
        captchaService.verify(scene, sourceKey, captchaId, captchaCode);
        captchaService.recordAttempt(scene, sourceKey);
    }

    @Override
    public <T> T guarded(String scene, String ipKey, String userKey, String captchaId, String captchaCode,
                         Supplier<T> action) {
        // 任一维度达阈值即强制校验（短路顺序保持 ipKey 优先，命中时不再查 userKey 维度）
        boolean captchaNeeded = captchaService.isRequired(scene, ipKey)
                || captchaService.isRequired(scene, userKey);
        try {
            if (captchaNeeded) {
                captchaService.verifyForced(scene, captchaId, captchaCode);
            }
            T result = action.get();
            // action 成功清零双维度计数
            captchaService.clearAttempts(scene, ipKey);
            captchaService.clearAttempts(scene, userKey);
            return result;
        } catch (BusinessException e) {
            // 失败（含验证码缺失/错误）双维度计数后原样抛出；非 BusinessException 不进此分支
            captchaService.recordAttempt(scene, ipKey);
            captchaService.recordAttempt(scene, userKey);
            throw e;
        }
    }
}
