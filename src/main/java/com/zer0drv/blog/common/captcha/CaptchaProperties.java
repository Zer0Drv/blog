package com.zer0drv.blog.common.captcha;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 自适应图形验证码配置（blog.captcha.*）：按 scene+来源计数，达到阈值后该来源才必须过验证码。
 * scenes 的 key 即场景名（login/register/email-code/password-reset/comment）。
 *
 * @author Yoruhaki
 */
@Data
@Component
@ConfigurationProperties(prefix = "blog.captcha")
public class CaptchaProperties {

    /**
     * 未配置场景的兜底阈值（次数）
     */
    public static final int DEFAULT_THRESHOLD = 5;

    /**
     * 未配置场景的兜底窗口（秒）
     */
    public static final long DEFAULT_WINDOW_SECONDS = 600;

    /**
     * 验证码文本 Redis TTL（秒），一次性，校验一次即删
     */
    private long codeTtlSeconds = 300;

    /**
     * 各场景阈值配置（scene -> 阈值/窗口）
     */
    private Map<String, Scene> scenes = new HashMap<>();

    /**
     * 取场景阈值，未配置时返回兜底值
     */
    public Scene sceneOf(String scene) {
        return scenes.getOrDefault(scene, new Scene(DEFAULT_THRESHOLD, DEFAULT_WINDOW_SECONDS));
    }

    /**
     * 单场景阈值配置
     */
    @Data
    public static class Scene {

        /**
         * 窗口内触发验证码的次数阈值（达到即要求验证码）
         */
        private int threshold = DEFAULT_THRESHOLD;

        /**
         * 计数窗口（秒）
         */
        private long windowSeconds = DEFAULT_WINDOW_SECONDS;

        public Scene() {
        }

        public Scene(int threshold, long windowSeconds) {
            this.threshold = threshold;
            this.windowSeconds = windowSeconds;
        }
    }
}
