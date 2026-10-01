package com.zer0drv.blog.auth.service;

/**
 * 邮箱验证码（注册/找回密码共用，按 scene 隔离）。
 *
 * @author Yoruhaki
 */
public interface EmailCodeService {

    String SCENE_REGISTER = "register";

    /**
     * 找回密码场景（与注册场景隔离，验证码不可跨场景复用）
     */
    String SCENE_RESET = "password_reset";

    /**
     * 发送验证码（60s 限频，10min 有效）。
     * 未配置 SMTP（spring.mail.host 缺失）时走 dev 兜底：验证码仅写入 Redis，
     * 日志不再打印明文（#3），用 redis-cli GET blog:email-code:<scene>:<email> 读取。
     *
     * @param scene 场景（register）
     * @param email 邮箱
     */
    void sendCode(String scene, String email);

    /**
     * 校验验证码（成功即消费，不可复用）。
     * 失败计数（#3）：同一 scene+email 连续失败 5 次即作废当前验证码（需重新发送）。
     *
     * @param scene 场景
     * @param email 邮箱
     * @param code  验证码
     * @return true=通过；false=错误/过期/已作废
     */
    boolean verify(String scene, String email, String code);
}
