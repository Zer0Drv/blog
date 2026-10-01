package com.zer0drv.blog.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * @author Yoruhaki
 */
@Data
public class RegisterDTO {

    /**
     * 密码复杂度（#6-2）：8~64 位，须同时包含字母和数字；注册/改密/重置三处统一
     */
    public static final String PASSWORD_PATTERN = "^(?=.*[A-Za-z])(?=.*\\d)\\S{8,64}$";

    /**
     * 密码复杂度提示文案（三处统一）
     */
    public static final String PASSWORD_MESSAGE = "密码须为 8~64 位，且同时包含字母和数字";

    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9_]{3,32}$", message = "用户名须为 3~32 位字母/数字/下划线")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度须在 8~64 之间")
    @Pattern(regexp = PASSWORD_PATTERN, message = PASSWORD_MESSAGE)
    private String password;

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    @NotBlank(message = "邮箱验证码不能为空")
    @Size(min = 6, max = 6, message = "验证码为 6 位数字")
    private String code;

    @Size(max = 32, message = "昵称最长 32 字符")
    private String nickname;

    /**
     * 图形验证码 id（触发频率阈值后必填，正常注册留空）
     */
    private String captchaId;

    /**
     * 图形验证码答案（触发频率阈值后必填）
     */
    private String captchaCode;
}
