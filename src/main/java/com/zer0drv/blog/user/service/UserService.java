package com.zer0drv.blog.user.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.dto.ProfileUpdateDTO;
import com.zer0drv.blog.user.vo.UserVO;

/**
 * @author Yoruhaki
 */
public interface UserService extends IService<User> {

    /**
     * 更新指定用户的个人资料（仅昵称/头像/简介）
     *
     * @param userId 用户 id
     * @param dto    资料更新请求
     * @return 更新后的用户信息
     */
    UserVO updateProfile(Long userId, ProfileUpdateDTO dto);
}
