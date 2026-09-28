package com.zer0drv.blog.user.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author Yoruhaki
 */
@Data
public class UserVO {

    private Long id;

    private String username;

    private String email;

    private String nickname;

    private String avatar;

    private String bio;

    private String role;

    private Short status;

    private LocalDateTime createTime;
}
