package com.zer0drv.blog.user.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.dto.ProfileUpdateDTO;
import com.zer0drv.blog.user.mapper.UserMapper;
import com.zer0drv.blog.user.service.UserService;
import com.zer0drv.blog.user.vo.UserVO;
import io.github.linpeilie.Converter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

/**
 * @author Yoruhaki
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    /**
     * 通知偏好键：评论邮件通知开关
     */
    private static final String PREF_EMAIL_NOTIFY_ENABLED = "emailNotifyEnabled";

    private final Converter converter;

    @Override
    public UserVO updateProfile(Long userId, ProfileUpdateDTO dto) {
        User user = requireUser(userId);
        // 仅更新资料三字段与更新时间，密码/角色/状态等其余字段保持原值
        user.setNickname(dto.getNickname());
        user.setAvatar(dto.getAvatar());
        user.setBio(dto.getBio());
        user.setUpdateTime(LocalDateTime.now());
        boolean updated = updateById(user);
        if (!updated) {
            throw new BusinessException(StatusCode.USER_UPDATE_FAILED);
        }
        return converter.convert(user, UserVO.class);
    }

    @Override
    public Map<String, Boolean> getPreferences(Long userId) {
        User user = requireUser(userId);
        return Map.of(PREF_EMAIL_NOTIFY_ENABLED, isEmailNotifyEnabled(user));
    }

    @Override
    public Map<String, Boolean> updatePreferences(Long userId, Map<String, Object> preferences) {
        User user = requireUser(userId);
        Object raw = Objects.nonNull(preferences) ? preferences.get(PREF_EMAIL_NOTIFY_ENABLED) : null;
        // 必传且必须为布尔（JSON true/false），其余一律参数错误
        if (!(raw instanceof Boolean enabled)) {
            throw new BusinessException(StatusCode.PARAM_INVALID);
        }
        user.setEmailNotifyEnabled((short) (enabled ? 1 : 0));
        user.setUpdateTime(LocalDateTime.now());
        boolean updated = updateById(user);
        if (!updated) {
            throw new BusinessException(StatusCode.USER_UPDATE_FAILED);
        }
        return Map.of(PREF_EMAIL_NOTIFY_ENABLED, enabled);
    }

    private User requireUser(Long userId) {
        User user = getById(userId);
        if (Objects.isNull(user)) {
            throw new BusinessException(StatusCode.USER_NOT_EXIST);
        }
        return user;
    }

    /**
     * 邮件通知开关兜底：列默认 1，存量/异常行为 null 时视为开启
     */
    private static boolean isEmailNotifyEnabled(User user) {
        return Objects.isNull(user.getEmailNotifyEnabled()) || user.getEmailNotifyEnabled() != 0;
    }
}
