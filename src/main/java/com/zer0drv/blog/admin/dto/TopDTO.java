package com.zer0drv.blog.admin.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 文章置顶切换入参。
 *
 * @author Yoruhaki
 */
@Data
public class TopDTO {

    /**
     * 置顶：0-否；1-是
     */
    @NotNull(message = "isTop 不能为空")
    private Short isTop;
}
