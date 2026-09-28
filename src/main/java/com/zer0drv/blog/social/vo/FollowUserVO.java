package com.zer0drv.blog.social.vo;

import lombok.Data;

/**
 * 粉丝 / 关注列表用户项
 *
 * @author Yoruhaki
 */
@Data
public class FollowUserVO {

    private Long id;

    private String username;

    private String nickname;

    private String avatar;

    private String bio;

    /**
     * 当前登录者是否已关注该用户（匿名一律 false）
     */
    private Boolean followed;
}
