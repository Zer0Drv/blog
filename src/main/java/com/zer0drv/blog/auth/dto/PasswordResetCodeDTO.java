package com.zer0drv.blog.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 找回密码：发送验证码
 *
 * @author Yoruhaki
 */
@Data
public class PasswordResetCodeDTO {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    /**
     * 图形验证码 id（触发频率阈值后必填，正常发送留空；#3 补齐与同类端点一致的验证码保护）
     */
    private String captchaId;

    /**
     * 图形验证码答案（触发频率阈值后必填）
     */
    private String captchaCode;
}
