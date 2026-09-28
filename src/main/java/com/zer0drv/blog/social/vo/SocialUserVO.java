package com.zer0drv.blog.social.vo;

import lombok.Data;

/**
 * 用户简要信息（通知 actor / 私信会话 peer 等场景复用）
 *
 * @author Yoruhaki
 */
@Data
public class SocialUserVO {

    private Long id;

    private String username;

    private String nickname;

    private String avatar;
}
