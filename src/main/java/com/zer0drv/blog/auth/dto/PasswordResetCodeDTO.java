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
}
