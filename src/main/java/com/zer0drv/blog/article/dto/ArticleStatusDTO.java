package com.zer0drv.blog.article.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 文章状态变更入参：上架 / 下架 / 回草稿
 *
 * @author Yoruhaki
 */
@Data
public class ArticleStatusDTO {

    /**
     * 目标状态：DRAFT / PUBLISHED / OFFLINE
     */
    @NotBlank(message = "状态不能为空")
    private String status;
}