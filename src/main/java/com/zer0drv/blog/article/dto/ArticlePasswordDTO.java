package com.zer0drv.blog.article.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 口令保护文章的口令校验入参（issue #15）。captchaId / captchaCode 在触发频率阈值后
 * 必填（自适应图形验证码，编排收口 CaptchaGuard，与登录同款双维度计数：IP + 文章 id）。
 *
 * @author Yoruhaki
 */
@Data
public class ArticlePasswordDTO {

    /**
     * 访问口令（明文，仅本次校验用，落库与日志均不出现）
     */
    @NotBlank(message = "访问口令不能为空")
    @Size(max = 64, message = "访问口令不能超过 64 个字符")
    private String password;

    /**
     * 图形验证码 id（触发限流后必填）
     */
    private String captchaId;

    /**
     * 图形验证码答案（触发限流后必填）
     */
    private String captchaCode;
}
