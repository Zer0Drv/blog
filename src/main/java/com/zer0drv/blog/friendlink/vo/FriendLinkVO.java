package com.zer0drv.blog.friendlink.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 友情链接视图（公众端与管理端共用；公众端只见启用项）
 *
 * @author Yoruhaki
 */
@Data
public class FriendLinkVO {

    private Long id;

    private String name;

    private String url;

    private String logo;

    private String description;

    private Integer sort;

    /**
     * 状态：1-启用 0-停用
     */
    private Short status;

    private LocalDateTime createTime;
}
