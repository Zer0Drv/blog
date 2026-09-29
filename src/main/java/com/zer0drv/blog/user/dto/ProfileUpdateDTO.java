package com.zer0drv.blog.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 个人资料更新请求
 *
 * @author Yoruhaki
 */
@Data
public class ProfileUpdateDTO {

    @NotBlank(message = "昵称不能为空")
    @Size(max = 20, message = "昵称最长 20 字符")
    private String nickname;

    @Size(max = 512, message = "头像 URL 最长 512 字符")
    private String avatar;

    @Size(max = 255, message = "个人简介最长 255 字符")
    private String bio;
}
