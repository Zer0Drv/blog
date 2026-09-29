package com.zer0drv.blog.common.captcha;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.StatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 自适应图形验证码服务实现：计数与验证码文本均存 Redis（StringRedisTemplate 连接懒加载）。
 *
 * @author Yoruhaki
 */
@Service
@RequiredArgsConstructor
public class CaptchaServiceImpl implements CaptchaService {

    /**
     * 验证码文本键：captcha:{scene}:{captchaId}
     */
    private static final String CODE_KEY = "captcha:%s:%s";

    /**
     * 来源计数键：captcha:count:{scene}:{sourceKey}
     */
    private static final String COUNT_KEY = "captcha:count:%s:%s";

    private final StringRedisTemplate stringRedisTemplate;
    private final CaptchaProperties captchaProperties;

    @Override
    public Map<String, String> generate(String scene) {
        // 线段干扰验证码：160x60，4 位字符，30 条干扰线
        LineCaptcha captcha = CaptchaUtil.createLineCaptcha(160, 60, 4, 30);
        String captchaId = UUID.randomUUID().toString();
        stringRedisTemplate.opsForValue().set(CODE_KEY.formatted(scene, captchaId), captcha.getCode(),
                Duration.ofSeconds(captchaProperties.getCodeTtlSeconds()));
        return Map.of(
                "captchaId", captchaId,
                "imageBase64", "data:image/png;base64," + captcha.getImageBase64());
    }

    @Override
    public boolean isRequired(String scene, String sourceKey) {
        String count = stringRedisTemplate.opsForValue().get(COUNT_KEY.formatted(scene, sourceKey));
        if (count == null) {
            return false;
        }
        try {
            return Long.parseLong(count) >= captchaProperties.sceneOf(scene).getThreshold();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public void recordAttempt(String scene, String sourceKey) {
        String key = COUNT_KEY.formatted(scene, sourceKey);
        Long count = stringRedisTemplate.opsForValue().increment(key);
        // 首次计数时套上窗口 TTL，窗口过期后重新计数
        if (count != null && count == 1L) {
            stringRedisTemplate.expire(key, Duration.ofSeconds(captchaProperties.sceneOf(scene).getWindowSeconds()));
        }
    }

    @Override
    public void clearAttempts(String scene, String sourceKey) {
        stringRedisTemplate.delete(COUNT_KEY.formatted(scene, sourceKey));
    }

    @Override
    public void verify(String scene, String sourceKey, String captchaId, String captchaCode) {
        if (!isRequired(scene, sourceKey)) {
            return;
        }
        if (captchaId == null || captchaId.isBlank() || captchaCode == null || captchaCode.isBlank()) {
            throw new BusinessException(StatusCode.CAPTCHA_REQUIRED);
        }
        String key = CODE_KEY.formatted(scene, captchaId);
        String stored = stringRedisTemplate.opsForValue().get(key);
        // 一次性：无论对错，校验一次即删
        stringRedisTemplate.delete(key);
        if (stored == null || !stored.equalsIgnoreCase(captchaCode.trim())) {
            throw new BusinessException(StatusCode.CAPTCHA_REQUIRED.getCode(), "验证码错误或已过期，请重试");
        }
    }
}
