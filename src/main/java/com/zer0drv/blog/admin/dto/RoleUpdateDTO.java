package com.zer0drv.blog.admin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 角色变更入参：仅允许 USER / AUTHOR（服务端强校验，不允许改成 ADMIN）。
 *
 * @author Yoruhaki
 */
@Data
public class RoleUpdateDTO {

    /**
     * 目标角色：USER / AUTHOR
     */
    @NotBlank(message = "角色不能为空")
    private String role;
}
