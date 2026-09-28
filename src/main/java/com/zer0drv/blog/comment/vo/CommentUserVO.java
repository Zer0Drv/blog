package com.zer0drv.blog.comment.vo;

import lombok.Data;

/**
 * 评论内的用户简要信息（评论人 / 被回复人）
 *
 * @author Yoruhaki
 */
@Data
public class CommentUserVO {

    private Long id;

    private String username;

    private String nickname;

    private String avatar;
}