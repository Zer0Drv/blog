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
import java.util.Objects;

/**
 * @author Yoruhaki
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private final Converter converter;

    @Override
    public UserVO updateProfile(Long userId, ProfileUpdateDTO dto) {
        User user = getById(userId);
        if (Objects.isNull(user)) {
            throw new BusinessException(StatusCode.USER_NOT_EXIST);
        }
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
}
