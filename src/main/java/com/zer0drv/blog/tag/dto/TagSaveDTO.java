package com.zer0drv.blog.tag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 标签新增/编辑入参
 *
 * @author Yoruhaki
 */
@Data
public class TagSaveDTO {

    /**
     * 标签名
     */
    @NotBlank(message = "标签名不能为空")
    @Size(max = 64, message = "标签名不能超过 64 个字符")
    private String name;
}