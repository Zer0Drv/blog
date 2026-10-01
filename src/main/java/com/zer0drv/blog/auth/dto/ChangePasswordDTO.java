package com.zer0drv.blog.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * @author Yoruhaki
 */
@Data
public class ChangePasswordDTO {

    @NotBlank(message = "旧密码不能为空")
    private String oldPassword;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度须在 8~64 之间")
    @Pattern(regexp = RegisterDTO.PASSWORD_PATTERN, message = RegisterDTO.PASSWORD_MESSAGE)
    private String newPassword;
}
