package com.zer0drv.blog.admin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 角色变更入参：USER / AUTHOR / ADMIN（服务端强校验合法值）。
 * 提拔 ADMIN 已放开（变更走 API 才能保证吊销事件闭环）；仍不允许对现有 ADMIN 账号操作。
 *
 * @author Yoruhaki
 */
@Data
public class RoleUpdateDTO {

    /**
     * 目标角色：USER / AUTHOR / ADMIN
     */
    @NotBlank(message = "角色不能为空")
    private String role;
}
