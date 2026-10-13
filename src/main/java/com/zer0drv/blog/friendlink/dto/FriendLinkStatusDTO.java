package com.zer0drv.blog.friendlink.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 启用/停用友情链接入参（status：1-启用 0-停用）。
 *
 * @author Yoruhaki
 */
@Data
public class FriendLinkStatusDTO {

    /**
     * 目标状态：1-启用 0-停用
     */
    @NotNull(message = "状态不能为空")
    private Short status;
}
