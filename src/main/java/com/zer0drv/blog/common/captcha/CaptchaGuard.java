package com.zer0drv.blog.common.captcha;

import java.util.function.Supplier;

/**
 * 验证码编排守卫（Issue #25 第二波 B）：「校验 → 计数 / 清零」编排的唯一归宿。
 *
 * <p>原子能力（阈值判定 / 一次性校验 / 计数 / 清零）仍在 {@link CaptchaService}；
 * 本接口只做编排收口——端点一律调它，不再逐端点自行组合 CaptchaService 调用，
 * 避免三段式编排在各端点重复后各自漂移。
 *
 * @author Yoruhaki
 */
public interface CaptchaGuard {

    /**
     * 简单端点编排（注册 / 邮箱码 / 重置码 / 重置 / 评论）：
     * 业务执行前 verify（未达阈值直接放行），通过后 recordAttempt。
     * 校验失败（缺参 / 错误或过期）抛 BusinessException，且本次尝试不计数。
     */
    void verifyAndRecord(String scene, String sourceKey, String captchaId, String captchaCode);

    /**
     * 登录型双维度编排：ipKey / userKey 任一达阈值即强制校验验证码（verifyForced，不查阈值）；
     * action 成功返回后清零两维；验证码校验或 action 抛 BusinessException 时两维各 +1 后原样抛出。
     * 非 BusinessException 不计数、直接抛出。
     */
    <T> T guarded(String scene, String ipKey, String userKey, String captchaId, String captchaCode,
                  Supplier<T> action);
}
