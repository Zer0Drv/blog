package com.zer0drv.blog.common.captcha;

import java.util.Map;

/**
 * 自适应图形验证码服务（频率触发）：按 scene+来源计数，达到阈值后该来源必须过验证码。
 * 验证码文本存 Redis，一次性（校验成功或失败一次即删）。
 *
 * @author Yoruhaki
 */
public interface CaptchaService {

    /**
     * 场景：账号密码登录（计数键：客户端 IP，失败 +1，成功清零）
     */
    String SCENE_LOGIN = "login";

    /**
     * 场景：注册（计数键：客户端 IP，尝试 +1）
     */
    String SCENE_REGISTER = "register";

    /**
     * 场景：发送注册邮箱验证码（计数键：客户端 IP，尝试 +1）
     */
    String SCENE_EMAIL_CODE = "email-code";

    /**
     * 场景：找回密码重置（计数键：客户端 IP，尝试 +1）
     */
    String SCENE_PASSWORD_RESET = "password-reset";

    /**
     * 场景：发表评论（计数键：当前用户 ID，尝试 +1）
     */
    String SCENE_COMMENT = "comment";

    /**
     * 生成图形验证码：文本存 Redis（TTL 见配置），返回 captchaId 与 PNG data URI
     *
     * @return { "captchaId": "uuid", "imageBase64": "data:image/png;base64,..." }
     */
    Map<String, String> generate(String scene);

    /**
     * 该来源当前是否必须先过图形验证码（计数达到阈值）
     */
    boolean isRequired(String scene, String sourceKey);

    /**
     * 记录一次尝试（窗口内计数 +1，首次写入设置窗口 TTL）
     */
    void recordAttempt(String scene, String sourceKey);

    /**
     * 清零该来源的计数（如登录成功）
     */
    void clearAttempts(String scene, String sourceKey);

    /**
     * 业务执行前校验：未达阈值直接放行；达阈值后缺参抛 CAPTCHA_REQUIRED，
     * 验证码错误或已过期抛同码值异常（message 为「验证码错误或已过期，请重试」）
     */
    void verify(String scene, String sourceKey, String captchaId, String captchaCode);
}
