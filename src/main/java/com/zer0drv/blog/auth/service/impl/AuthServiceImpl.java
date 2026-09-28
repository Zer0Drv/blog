package com.zer0drv.blog.auth.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zer0drv.blog.auth.dto.ChangePasswordDTO;
import com.zer0drv.blog.auth.dto.RegisterDTO;
import com.zer0drv.blog.auth.dto.UserLoginDTO;
import com.zer0drv.blog.auth.service.AuthService;
import com.zer0drv.blog.auth.service.EmailCodeService;
import com.zer0drv.blog.auth.service.TokenService;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.common.util.JwtSubjects;
import com.zer0drv.blog.user.bo.SecurityUser;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.enums.UserRole;
import com.zer0drv.blog.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * @author Yoruhaki
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;
    private final UserService userService;
    private final EmailCodeService emailCodeService;
    private final PasswordEncoder passwordEncoder;

    @Override
    public Map<String, String> userLogin(UserLoginDTO dto) {
        Authentication authenticate;
        try {
            authenticate = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            dto.getUsername(), dto.getPassword()));
        } catch (DisabledException e) {
            throw new BusinessException(StatusCode.USER_BANNED);
        } catch (BadCredentialsException e) {
            throw new BusinessException(StatusCode.USER_PASSWORD_ERROR);
        }
        List<@Nullable String> roles = authenticate.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .filter(role -> role.startsWith("ROLE_"))
                .toList();
        SecurityUser principal = (SecurityUser) authenticate.getPrincipal();
        if (Objects.isNull(principal)) {
            throw new BusinessException(StatusCode.USER_NOT_EXIST);
        }
        String token = tokenService.generateToken(principal.getId(), roles);
        return Map.of("access_token", token, "token_type", "Bearer");
    }

    @Override
    public Map<String, String> register(RegisterDTO dto) {
        if (!emailCodeService.verify(EmailCodeService.SCENE_REGISTER, dto.getEmail(), dto.getCode())) {
            throw new BusinessException(StatusCode.EMAIL_CODE_INVALID);
        }
        long usernameCount = userService.count(Wrappers.lambdaQuery(User.class)
                .eq(User::getUsername, dto.getUsername()));
        if (usernameCount > 0) {
            throw new BusinessException(StatusCode.USERNAME_EXISTS);
        }
        long emailCount = userService.count(Wrappers.lambdaQuery(User.class)
                .eq(User::getEmail, dto.getEmail()));
        if (emailCount > 0) {
            throw new BusinessException(StatusCode.EMAIL_EXISTS);
        }
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setEmail(dto.getEmail());
        user.setNickname(Objects.isNull(dto.getNickname()) || dto.getNickname().isBlank()
                ? dto.getUsername() : dto.getNickname());
        user.setRole(UserRole.USER.name());
        user.setStatus((short) 0);
        boolean saved = userService.save(user);
        if (!saved) {
            throw new BusinessException(StatusCode.USER_CREATE_FAILED);
        }
        String token = tokenService.generateToken(user.getId(), List.of("ROLE_" + UserRole.USER.name()));
        return Map.of("access_token", token, "token_type", "Bearer");
    }

    @Override
    public String userLogout(Jwt jwt) {
        String jti = jwt.getId();
        Instant expiresAt = jwt.getExpiresAt();
        tokenService.blackToken(jti, expiresAt);
        return "success";
    }

    @Override
    public User getProfile(Jwt jwt) {
        Long userId = JwtSubjects.userIdOf(jwt);
        User user = userService.getById(userId);
        if (Objects.isNull(user)) {
            throw new BusinessException(StatusCode.USER_NOT_EXIST_OR_DELETED);
        }
        return user;
    }

    @Override
    public void changePassword(Jwt jwt, ChangePasswordDTO dto) {
        Long userId = JwtSubjects.userIdOf(jwt);
        User user = userService.getById(userId);
        if (Objects.isNull(user)) {
            throw new BusinessException(StatusCode.USER_NOT_EXIST);
        }
        if (!passwordEncoder.matches(dto.getOldPassword(), user.getPassword())) {
            throw new BusinessException(StatusCode.PASSWORD_NOT_MATCH);
        }
        tokenService.blackToken(jwt.getId(), jwt.getExpiresAt());
        user.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        boolean updated = userService.updateById(user);
        if (!updated) {
            throw new BusinessException(StatusCode.USER_UPDATE_FAILED);
        }
    }
}
