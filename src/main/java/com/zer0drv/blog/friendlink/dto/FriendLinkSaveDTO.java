package com.zer0drv.blog.friendlink.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增/编辑友情链接入参。
 *
 * @author Yoruhaki
 */
@Data
public class FriendLinkSaveDTO {

    /**
     * 站点名称（≤64，落库前 trim）
     */
    @NotBlank(message = "名称不能为空")
    @Size(max = 64, message = "名称长度不能超过 64 个字符")
    private String name;

    /**
     * 链接地址（http/https，≤512，落库前 trim）
     */
    @NotBlank(message = "链接地址不能为空")
    @Size(max = 512, message = "链接地址长度不能超过 512 个字符")
    private String url;

    /**
     * Logo 图片 URL（可空，≤512）
     */
    @Size(max = 512, message = "Logo 地址长度不能超过 512 个字符")
    private String logo;

    /**
     * 一句话描述（可空，≤255）
     */
    @Size(max = 255, message = "描述长度不能超过 255 个字符")
    private String description;

    /**
     * 排序值（升序，越小越靠前；缺省 0）
     */
    @NotNull(message = "排序值不能为空")
    private Integer sort = 0;
}
