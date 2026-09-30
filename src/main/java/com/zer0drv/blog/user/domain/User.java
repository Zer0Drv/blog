package com.zer0drv.blog.user.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zer0drv.blog.user.bo.SecurityUser;
import com.zer0drv.blog.user.vo.UserVO;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表
 *
 * @author Yoruhaki
 */
@Data
@TableName(value = "user")
@AutoMapper(target = SecurityUser.class, reverseConvertGenerate = false)
@AutoMapper(target = UserVO.class, reverseConvertGenerate = false)
public class User {

    /**
     * 用户id
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户名
     */
    @TableField(value = "username")
    private String username;

    /**
     * 密码（BCrypt）
     */
    @TableField(value = "password")
    private String password;

    /**
     * 邮箱
     */
    @TableField(value = "email")
    private String email;

    /**
     * 昵称
     */
    @TableField(value = "nickname")
    private String nickname;

    /**
     * 头像 URL
     */
    @TableField(value = "avatar")
    private String avatar;

    /**
     * 个人简介
     */
    @TableField(value = "bio")
    private String bio;

    /**
     * 角色：ADMIN / AUTHOR / USER
     */
    @TableField(value = "role")
    private String role;

    /**
     * 状态：0-正常；1-封禁
     */
    @TableField(value = "status")
    private Short status;

    /**
     * GitHub 账号 id（OAuth 登录绑定）
     */
    @TableField(value = "github_id")
    private Long githubId;

    /**
     * 评论邮件通知开关（P0 §2.3）：0-关；1-开
     */
    @TableField(value = "email_notify_enabled")
    private Short emailNotifyEnabled;

    /**
     * 创建时间
     */
    @TableField(value = "create_time")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField(value = "update_time")
    private LocalDateTime updateTime;

    /**
     * 逻辑删除：0-未删除；1-删除
     */
    @TableField(value = "deleted")
    private Short deleted;
}
