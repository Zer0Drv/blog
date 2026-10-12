package com.zer0drv.blog.user.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.dto.ProfileUpdateDTO;
import com.zer0drv.blog.user.vo.UserVO;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
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

    /**
     * 全站用户总数（管理端统计用，issue #34 第五步；逻辑删除由 MP 全局过滤）
     */
    long countAllUsers();

    /**
     * create_time 不早于 since 的用户数（管理端「今日新增用户」口径）
     */
    long countUsersCreatedSince(LocalDateTime since);

    /**
     * 最新注册用户投影（id 倒序，至多 limit 条；管理端统计用）
     */
    List<UserVO> listLatestUsers(int limit);

    /**
     * 按 id 集合批量查用户投影（管理端统计的用户名 / 昵称联查用）；
     * ids 为空返回空表（不触库），查不到的 id 不出现在结果中
     */
    Map<Long, UserVO> mapUsersByIds(Collection<Long> ids);
}
