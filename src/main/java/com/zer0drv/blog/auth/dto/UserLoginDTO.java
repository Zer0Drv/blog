package com.zer0drv.blog.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * @author Yoruhaki
 */
@Data
public class UserLoginDTO {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度须在 6~64 之间")
    private String password;

    /**
     * 图形验证码 id（触发频率阈值后必填，正常登录留空）
     */
    private String captchaId;

    /**
     * 图形验证码答案（触发频率阈值后必填）
     */
    private String captchaCode;
}
