package com.zer0drv.blog.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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
    @Size(min = 6, max = 32, message = "密码长度须在 6~32 之间")
    private String newPassword;
}
