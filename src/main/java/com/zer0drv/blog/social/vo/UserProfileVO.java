package com.zer0drv.blog.social.vo;

import lombok.Data;

/**
 * 用户公开主页信息
 *
 * @author Yoruhaki
 */
@Data
public class UserProfileVO {

    private Long id;

    private String username;

    private String nickname;

    private String avatar;

    private String bio;

    /**
     * 粉丝数
     */
    private Long followerCount;

    /**
     * 关注数
     */
    private Long followingCount;

    /**
     * PUBLISHED 文章数
     */
    private Long articleCount;

    /**
     * 当前登录者是否已关注该用户（匿名一律 false）
     */
    private Boolean followed;
}
