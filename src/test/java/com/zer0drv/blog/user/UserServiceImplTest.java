package com.zer0drv.blog.user;

import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.dto.ProfileUpdateDTO;
import com.zer0drv.blog.user.mapper.UserMapper;
import com.zer0drv.blog.user.service.impl.UserServiceImpl;
import com.zer0drv.blog.user.vo.UserVO;
import io.github.linpeilie.Converter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserServiceImpl 纯单测：个人资料更新。
 * 昵称空白/长度等入参校验由 DTO 层（Bean Validation）负责，此处只测更新逻辑与异常分支。
 *
 * @author Yoruhaki
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private Converter converter;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl(converter);
        // ServiceImpl 的 baseMapper 在父类 CrudRepository 上声明，手动注入 mock
        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);
    }

    private static User user(long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("user" + id);
        user.setPassword("ENCODED");
        user.setEmail("user" + id + "@example.com");
        user.setNickname("旧昵称");
        user.setAvatar("https://example.com/old.png");
        user.setBio("旧简介");
        user.setRole("USER");
        user.setStatus((short) 0);
        user.setUpdateTime(LocalDateTime.of(2026, 1, 1, 0, 0));
        return user;
    }

    private static ProfileUpdateDTO dto(String nickname, String avatar, String bio) {
        ProfileUpdateDTO dto = new ProfileUpdateDTO();
        dto.setNickname(nickname);
        dto.setAvatar(avatar);
        dto.setBio(bio);
        return dto;
    }

    @Test
    void updateProfile_success_updatesThreeFieldsAndUpdateTime() {
        User existing = user(7L);
        when(userMapper.selectById(7L)).thenReturn(existing);
        when(userMapper.updateById(any(User.class))).thenReturn(1);
        UserVO vo = new UserVO();
        vo.setId(7L);
        vo.setNickname("新昵称");
        when(converter.convert(any(User.class), any())).thenReturn(vo);

        UserVO result = userService.updateProfile(7L, dto("新昵称", "https://example.com/new.png", "新简介"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        User updated = captor.getValue();
        // 资料三字段已更新
        assertEquals("新昵称", updated.getNickname());
        assertEquals("https://example.com/new.png", updated.getAvatar());
        assertEquals("新简介", updated.getBio());
        // 更新时间被刷新
        assertNotNull(updated.getUpdateTime());
        // 其余字段保持原值不动
        assertEquals("user7", updated.getUsername());
        assertEquals("ENCODED", updated.getPassword());
        assertEquals("user7@example.com", updated.getEmail());
        assertEquals("USER", updated.getRole());
        assertEquals((short) 0, updated.getStatus());
        // 返回转换后的 UserVO
        assertEquals(7L, result.getId());
        assertEquals("新昵称", result.getNickname());
    }

    @Test
    void updateProfile_emptyAvatarAndBio_allowed() {
        User existing = user(7L);
        when(userMapper.selectById(7L)).thenReturn(existing);
        when(userMapper.updateById(any(User.class))).thenReturn(1);
        when(converter.convert(any(User.class), any())).thenReturn(new UserVO());

        userService.updateProfile(7L, dto("新昵称", "", ""));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        // 头像/简介可置为空串
        assertEquals("", captor.getValue().getAvatar());
        assertEquals("", captor.getValue().getBio());
    }

    @Test
    void updateProfile_userNotExist_throwsAndNeverUpdates() {
        when(userMapper.selectById(404L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.updateProfile(404L, dto("新昵称", "", "")));
        assertEquals(StatusCode.USER_NOT_EXIST.getCode(), ex.getCode());
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void getPreferences_nullColumn_defaultsTrue() {
        // P0 §2.3：存量行/异常行为 null 时视为开启
        User existing = user(7L);
        existing.setEmailNotifyEnabled(null);
        when(userMapper.selectById(7L)).thenReturn(existing);

        Map<String, Boolean> preferences = userService.getPreferences(7L);

        assertEquals(Boolean.TRUE, preferences.get("emailNotifyEnabled"));
    }

    @Test
    void getPreferences_disabled_returnsFalse() {
        User existing = user(7L);
        existing.setEmailNotifyEnabled((short) 0);
        when(userMapper.selectById(7L)).thenReturn(existing);

        Map<String, Boolean> preferences = userService.getPreferences(7L);

        assertEquals(Boolean.FALSE, preferences.get("emailNotifyEnabled"));
    }

    @Test
    void updatePreferences_boolean_updatesAndReturnsLatest() {
        User existing = user(7L);
        existing.setEmailNotifyEnabled((short) 1);
        when(userMapper.selectById(7L)).thenReturn(existing);
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        Map<String, Boolean> result = userService.updatePreferences(7L, Map.of("emailNotifyEnabled", false));

        assertEquals(Boolean.FALSE, result.get("emailNotifyEnabled"));
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertEquals((short) 0, captor.getValue().getEmailNotifyEnabled());
    }

    @Test
    void updatePreferences_nonBoolean_rejected() {
        // 非布尔（字符串）→ PARAM_INVALID
        User existing = user(7L);
        when(userMapper.selectById(7L)).thenReturn(existing);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.updatePreferences(7L, Map.of("emailNotifyEnabled", "yes")));
        assertEquals(StatusCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void updatePreferences_missingKey_rejected() {
        // 必传：缺 key 或 body 为 null 均 PARAM_INVALID
        User existing = user(7L);
        when(userMapper.selectById(7L)).thenReturn(existing);

        assertThrows(BusinessException.class,
                () -> userService.updatePreferences(7L, Map.of()));
        assertThrows(BusinessException.class,
                () -> userService.updatePreferences(7L, null));
        verify(userMapper, never()).updateById(any(User.class));
    }
}
