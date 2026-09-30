package com.zer0drv.blog.user.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.dto.ProfileUpdateDTO;
import com.zer0drv.blog.user.vo.UserVO;

import java.util.Map;

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

    /**
     * 查询指定用户的通知偏好（P0 §2.3）
     *
     * @param userId 用户 id
     * @return {emailNotifyEnabled: true|false}
     */
    Map<String, Boolean> getPreferences(Long userId);

    /**
     * 更新指定用户的通知偏好（P0 §2.3）。
     * body 必须包含布尔值 emailNotifyEnabled，缺失或非布尔 → PARAM_INVALID。
     *
     * @param userId      用户 id
     * @param preferences 偏好请求体
     * @return 更新后的最新偏好
     */
    Map<String, Boolean> updatePreferences(Long userId, Map<String, Object> preferences);
}
