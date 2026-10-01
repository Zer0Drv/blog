package com.zer0drv.blog.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 找回密码：验证码 + 新密码重置
 *
 * @author Yoruhaki
 */
@Data
public class PasswordResetDTO {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    @NotBlank(message = "邮箱验证码不能为空")
    private String code;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度须在 8~64 之间")
    @Pattern(regexp = RegisterDTO.PASSWORD_PATTERN, message = RegisterDTO.PASSWORD_MESSAGE)
    private String newPassword;

    /**
     * 图形验证码 id（触发频率阈值后必填，正常重置留空）
     */
    private String captchaId;

    /**
     * 图形验证码答案（触发频率阈值后必填）
     */
    private String captchaCode;
}
