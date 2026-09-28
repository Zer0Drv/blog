package com.zer0drv.blog.admin.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 文章推荐位切换入参。
 *
 * @author Yoruhaki
 */
@Data
public class RecommendDTO {

    /**
     * 推荐位：0-否；1-是
     */
    @NotNull(message = "isRecommended 不能为空")
    private Short isRecommended;
}
