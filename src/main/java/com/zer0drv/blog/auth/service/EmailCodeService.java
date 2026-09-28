package com.zer0drv.blog.auth.service;

/**
 * 邮箱验证码（注册/找回密码共用，按 scene 隔离）。
 *
 * @author Yoruhaki
 */
public interface EmailCodeService {

    String SCENE_REGISTER = "register";

    /**
     * 发送验证码（60s 限频，10min 有效）。
     * 未配置 SMTP（spring.mail.host 缺失）时走 dev 兜底：验证码打印到后端日志。
     *
     * @param scene 场景（register）
     * @param email 邮箱
     */
    void sendCode(String scene, String email);

    /**
     * 校验验证码（成功即消费，不可复用）。
     *
     * @param scene 场景
     * @param email 邮箱
     * @param code  验证码
     * @return true=通过；false=错误/过期
     */
    boolean verify(String scene, String email, String code);
}
