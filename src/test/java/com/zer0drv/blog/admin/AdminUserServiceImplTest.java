package com.zer0drv.blog.admin;

import com.zer0drv.blog.admin.dto.RoleUpdateDTO;
import com.zer0drv.blog.admin.service.impl.AdminUserServiceImpl;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.enums.UserRole;
import com.zer0drv.blog.user.service.UserService;
import io.github.linpeilie.Converter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理员用户操作边界纯单测：ban 管理员/自己拒绝、非法角色拒绝。
 *
 * @author Yoruhaki
 */
@ExtendWith(MockitoExtension.class)
class AdminUserServiceImplTest {

    @Mock
    private UserService userService;
    @Mock
    private Converter converter;

    @InjectMocks
    private AdminUserServiceImpl adminUserService;

    private static Jwt adminJwt(long userId) {
        return new Jwt("tk", Instant.now(), Instant.now().plusSeconds(3600),
                Map.of("alg", "HS256"),
                Map.of("sub", String.valueOf(userId), "roles", List.of("ROLE_ADMIN")));
    }

    private static User user(long id, String role) {
        User user = new User();
        user.setId(id);
        user.setUsername("user" + id);
        user.setRole(role);
        user.setStatus((short) 0);
        return user;
    }

    @Test
    void ban_adminTarget_rejected() {
        when(userService.getById(1L)).thenReturn(user(1L, UserRole.ADMIN.name()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminUserService.ban(1L, adminJwt(99L)));
        assertEquals(StatusCode.CANNOT_OPERATE_ADMIN.getCode(), ex.getCode());
        verify(userService, never()).updateById(any());
    }

    @Test
    void ban_self_rejected() {
        when(userService.getById(5L)).thenReturn(user(5L, UserRole.USER.name()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminUserService.ban(5L, adminJwt(5L)));
        assertEquals(StatusCode.CANNOT_OPERATE_SELF.getCode(), ex.getCode());
        verify(userService, never()).updateById(any());
    }

    @Test
    void ban_normalUser_setsBannedStatus() {
        User target = user(6L, UserRole.USER.name());
        when(userService.getById(6L)).thenReturn(target);
        when(userService.updateById(any(User.class))).thenReturn(true);

        adminUserService.ban(6L, adminJwt(1L));

        assertEquals((short) 1, target.getStatus());
        verify(userService).updateById(target);
    }

    @Test
    void ban_missingUser_rejected() {
        when(userService.getById(404L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminUserService.ban(404L, adminJwt(1L)));
        assertEquals(StatusCode.USER_NOT_EXIST.getCode(), ex.getCode());
    }

    @Test
    void updateRole_invalidRole_rejected() {
        RoleUpdateDTO dto = new RoleUpdateDTO();
        dto.setRole("SUPERMAN");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminUserService.updateRole(6L, dto, adminJwt(1L)));
        assertEquals(StatusCode.ROLE_CHOICE_ERROR.getCode(), ex.getCode());
        verify(userService, never()).updateById(any());
    }

    @Test
    void updateRole_toAdmin_rejected() {
        RoleUpdateDTO dto = new RoleUpdateDTO();
        dto.setRole(UserRole.ADMIN.name());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminUserService.updateRole(6L, dto, adminJwt(1L)));
        assertEquals(StatusCode.CANNOT_OPERATE_ADMIN.getCode(), ex.getCode());
        verify(userService, never()).updateById(any());
    }

    @Test
    void updateRole_adminTarget_rejected() {
        RoleUpdateDTO dto = new RoleUpdateDTO();
        dto.setRole(UserRole.AUTHOR.name());
        when(userService.getById(2L)).thenReturn(user(2L, UserRole.ADMIN.name()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminUserService.updateRole(2L, dto, adminJwt(1L)));
        assertEquals(StatusCode.CANNOT_OPERATE_ADMIN.getCode(), ex.getCode());
        verify(userService, never()).updateById(any());
    }

    @Test
    void updateRole_validChange_updatesRole() {
        RoleUpdateDTO dto = new RoleUpdateDTO();
        dto.setRole(UserRole.AUTHOR.name());
        User target = user(6L, UserRole.USER.name());
        when(userService.getById(6L)).thenReturn(target);
        when(userService.updateById(any(User.class))).thenReturn(true);

        adminUserService.updateRole(6L, dto, adminJwt(1L));

        assertEquals(UserRole.AUTHOR.name(), target.getRole());
        verify(userService).updateById(target);
    }
}
