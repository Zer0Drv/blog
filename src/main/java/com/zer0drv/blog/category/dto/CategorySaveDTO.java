package com.zer0drv.blog.category.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 分类新增/编辑入参
 *
 * @author Yoruhaki
 */
@Data
public class CategorySaveDTO {

    /**
     * 分类名
     */
    @NotBlank(message = "分类名不能为空")
    @Size(max = 64, message = "分类名不能超过 64 个字符")
    private String name;

    /**
     * 父分类id：缺省 0=根分类
     */
    private Long parentId;

    /**
     * 排序值，越小越靠前
     */
    private Integer sort;
}