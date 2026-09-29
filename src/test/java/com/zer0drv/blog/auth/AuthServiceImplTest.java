package com.zer0drv.blog.auth;

import com.zer0drv.blog.auth.dto.PasswordResetDTO;
import com.zer0drv.blog.auth.dto.RegisterDTO;
import com.zer0drv.blog.auth.dto.UserLoginDTO;
import com.zer0drv.blog.auth.service.EmailCodeService;
import com.zer0drv.blog.auth.service.TokenService;
import com.zer0drv.blog.auth.service.impl.AuthServiceImpl;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.user.bo.SecurityUser;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.enums.UserRole;
import com.zer0drv.blog.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthServiceImpl 纯单测：登录 / 注册 / GitHub OAuth / 找回密码。
 *
 * @author Yoruhaki
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private TokenService tokenService;
    @Mock
    private UserService userService;
    @Mock
    private EmailCodeService emailCodeService;
    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthServiceImpl authService;

    private static User user(long id, String role, short status) {
        User user = new User();
        user.setId(id);
        user.setUsername("user" + id);
        user.setEmail("user" + id + "@example.com");
        user.setRole(role);
        user.setStatus(status);
        return user;
    }

    // ---------- 登录 ----------

    @Test
    void userLogin_success_returnsTokenAndRoles() {
        UserLoginDTO dto = new UserLoginDTO();
        dto.setUsername("alice");
        dto.setPassword("secret1");

        SecurityUser principal = new SecurityUser();
        principal.setId(7L);
        principal.setRole(UserRole.USER.name());
        Authentication authentication = mock(Authentication.class);
        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        doReturn(List.of(new SimpleGrantedAuthority("ROLE_USER"))).when(authentication).getAuthorities();
        when(authentication.getPrincipal()).thenReturn(principal);
        when(tokenService.generateToken(eq(7L), anyList())).thenReturn("jwt-token");

        Map<String, String> result = authService.userLogin(dto);

        assertEquals("jwt-token", result.get("access_token"));
        assertEquals("Bearer", result.get("token_type"));
        ArgumentCaptor<List<String>> rolesCaptor = ArgumentCaptor.forClass(List.class);
        verify(tokenService).generateToken(eq(7L), rolesCaptor.capture());
        assertEquals(List.of("ROLE_USER"), rolesCaptor.getValue());
    }

    @Test
    void userLogin_wrongPassword_throwsUserPasswordError() {
        UserLoginDTO dto = new UserLoginDTO();
        dto.setUsername("alice");
        dto.setPassword("wrong");
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("bad credentials"));

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.userLogin(dto));
        assertEquals(StatusCode.USER_PASSWORD_ERROR.getCode(), ex.getCode());
    }

    @Test
    void userLogin_banned_throwsUserBanned() {
        UserLoginDTO dto = new UserLoginDTO();
        dto.setUsername("alice");
        dto.setPassword("secret1");
        when(authenticationManager.authenticate(any()))
                .thenThrow(new DisabledException("disabled"));

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.userLogin(dto));
        assertEquals(StatusCode.USER_BANNED.getCode(), ex.getCode());
    }

    // ---------- 注册 ----------

    @Test
    void register_success_savesUserAndReturnsToken() {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername("bob");
        dto.setPassword("secret1");
        dto.setEmail("bob@example.com");
        dto.setCode("123456");
        when(emailCodeService.verify(EmailCodeService.SCENE_REGISTER, "bob@example.com", "123456"))
                .thenReturn(true);
        when(userService.count(any())).thenReturn(0L);
        when(passwordEncoder.encode("secret1")).thenReturn("ENCODED");
        when(userService.save(any(User.class))).thenReturn(true);
        when(tokenService.generateToken(any(), anyList())).thenReturn("reg-token");

        Map<String, String> result = authService.register(dto);

        assertEquals("reg-token", result.get("access_token"));
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userService).save(captor.capture());
        User saved = captor.getValue();
        assertEquals("bob", saved.getUsername());
        assertEquals("ENCODED", saved.getPassword());
        assertEquals("bob@example.com", saved.getEmail());
        // 昵称缺省回落用户名；角色 USER；状态正常
        assertEquals("bob", saved.getNickname());
        assertEquals(UserRole.USER.name(), saved.getRole());
        assertEquals((short) 0, saved.getStatus());
    }

    @Test
    void register_invalidEmailCode_throwsAndNeverSaves() {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername("bob");
        dto.setPassword("secret1");
        dto.setEmail("bob@example.com");
        dto.setCode("000000");
        when(emailCodeService.verify(anyString(), anyString(), anyString())).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.register(dto));
        assertEquals(StatusCode.EMAIL_CODE_INVALID.getCode(), ex.getCode());
        verify(userService, never()).save(any());
    }

    @Test
    void register_usernameExists_throws() {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername("bob");
        dto.setPassword("secret1");
        dto.setEmail("bob@example.com");
        dto.setCode("123456");
        when(emailCodeService.verify(anyString(), anyString(), anyString())).thenReturn(true);
        when(userService.count(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.register(dto));
        assertEquals(StatusCode.USERNAME_EXISTS.getCode(), ex.getCode());
        verify(userService, never()).save(any());
    }

    // ---------- GitHub OAuth ----------

    @Test
    void loginByGithub_boundUser_issuesTokenDirectly() {
        User bound = user(3L, UserRole.USER.name(), (short) 0);
        when(userService.getOne(any())).thenReturn(bound);
        when(tokenService.generateToken(eq(3L), anyList())).thenReturn("gh-token");

        Map<String, String> result = authService.loginByGithub(99L, "octo", "Octo", null, null);

        assertEquals("gh-token", result.get("access_token"));
        verify(userService, never()).save(any());
        verify(userService, never()).updateById(any());
    }

    @Test
    void loginByGithub_boundButBanned_rejected() {
        User banned = user(3L, UserRole.USER.name(), (short) 1);
        when(userService.getOne(any())).thenReturn(banned);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.loginByGithub(99L, "octo", null, null, null));
        assertEquals(StatusCode.USER_BANNED.getCode(), ex.getCode());
        verify(tokenService, never()).generateToken(any(), anyList());
    }

    @Test
    void loginByGithub_unbound_autoRegistersWithFallbackEmail() {
        when(userService.getOne(any())).thenReturn(null);
        when(userService.count(any())).thenReturn(0L);
        when(userService.save(any(User.class))).thenReturn(true);
        when(tokenService.generateToken(any(), anyList())).thenReturn("new-token");

        Map<String, String> result = authService.loginByGithub(42L, "octo", null, null, null);

        assertEquals("new-token", result.get("access_token"));
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userService).save(captor.capture());
        User saved = captor.getValue();
        assertEquals("gh_octo", saved.getUsername());
        // OAuth 占位账号：密码空串（BCrypt 永不匹配），隐藏邮箱用占位邮箱
        assertEquals("", saved.getPassword());
        assertEquals("gh_42@oauth.local", saved.getEmail());
        assertEquals(42L, saved.getGithubId());
    }

    @Test
    void loginByGithub_sameEmail_bindsExistingAccount() {
        User existing = user(9L, UserRole.USER.name(), (short) 0);
        // 第一次按 githubId 查未命中，第二次按邮箱查到既有账号
        when(userService.getOne(any())).thenReturn(null, existing);
        when(userService.updateById(any(User.class))).thenReturn(true);
        when(tokenService.generateToken(eq(9L), anyList())).thenReturn("bound-token");

        Map<String, String> result = authService.loginByGithub(55L, "octo", null, null, "user9@example.com");

        assertEquals("bound-token", result.get("access_token"));
        assertEquals(55L, existing.getGithubId());
        verify(userService).updateById(existing);
        verify(userService, never()).save(any());
    }

    // ---------- 找回密码 ----------

    @Test
    void sendPasswordResetCode_unregisteredEmail_throws() {
        when(userService.count(any())).thenReturn(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.sendPasswordResetCode("ghost@example.com"));
        assertEquals(StatusCode.EMAIL_NOT_REGISTERED.getCode(), ex.getCode());
        verify(emailCodeService, never()).sendCode(anyString(), anyString());
    }

    @Test
    void sendPasswordResetCode_registeredEmail_sendsResetSceneCode() {
        when(userService.count(any())).thenReturn(1L);

        authService.sendPasswordResetCode("bob@example.com");

        verify(emailCodeService).sendCode(EmailCodeService.SCENE_RESET, "bob@example.com");
    }

    @Test
    void resetPassword_invalidCode_throws() {
        PasswordResetDTO dto = new PasswordResetDTO();
        dto.setEmail("bob@example.com");
        dto.setCode("000000");
        dto.setNewPassword("newpass1");
        when(emailCodeService.verify(anyString(), anyString(), anyString())).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.resetPassword(dto));
        assertEquals(StatusCode.EMAIL_CODE_INVALID.getCode(), ex.getCode());
        verify(userService, never()).updateById(any());
    }

    @Test
    void resetPassword_success_encodesAndUpdates() {
        PasswordResetDTO dto = new PasswordResetDTO();
        dto.setEmail("bob@example.com");
        dto.setCode("123456");
        dto.setNewPassword("newpass1");
        User user = user(5L, UserRole.USER.name(), (short) 0);
        user.setPassword("");
        when(emailCodeService.verify(EmailCodeService.SCENE_RESET, "bob@example.com", "123456"))
                .thenReturn(true);
        when(userService.getOne(any())).thenReturn(user);
        when(passwordEncoder.encode("newpass1")).thenReturn("ENCODED2");
        when(userService.updateById(any(User.class))).thenReturn(true);

        authService.resetPassword(dto);

        assertEquals("ENCODED2", user.getPassword());
        verify(userService).updateById(user);
    }

    @Test
    void resetPassword_emailNotFoundAfterVerify_throws() {
        PasswordResetDTO dto = new PasswordResetDTO();
        dto.setEmail("ghost@example.com");
        dto.setCode("123456");
        dto.setNewPassword("newpass1");
        when(emailCodeService.verify(anyString(), anyString(), anyString())).thenReturn(true);
        when(userService.getOne(any())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.resetPassword(dto));
        assertEquals(StatusCode.EMAIL_NOT_REGISTERED.getCode(), ex.getCode());
        verify(userService, never()).updateById(any());
    }

    @Test
    void register_blankNickname_fallsBackToUsername() {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername("carol");
        dto.setPassword("secret1");
        dto.setEmail("carol@example.com");
        dto.setCode("123456");
        dto.setNickname("   ");
        when(emailCodeService.verify(anyString(), anyString(), anyString())).thenReturn(true);
        when(userService.count(any())).thenReturn(0L);
        when(passwordEncoder.encode(anyString())).thenReturn("ENC");
        when(userService.save(any(User.class))).thenReturn(true);
        when(tokenService.generateToken(any(), anyList())).thenReturn("t");

        authService.register(dto);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userService).save(captor.capture());
        assertEquals("carol", captor.getValue().getNickname());
        assertTrue(captor.getValue().getPassword().startsWith("ENC"));
    }
}
