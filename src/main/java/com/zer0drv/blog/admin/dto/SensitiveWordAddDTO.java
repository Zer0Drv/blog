package com.zer0drv.blog.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增敏感词入参（重复词静默成功）。
 *
 * @author Yoruhaki
 */
@Data
public class SensitiveWordAddDTO {

    /**
     * 敏感词（≤64，落库前 trim）
     */
    @NotBlank(message = "敏感词不能为空")
    @Size(max = 64, message = "敏感词长度不能超过 64 个字符")
    private String word;
}
