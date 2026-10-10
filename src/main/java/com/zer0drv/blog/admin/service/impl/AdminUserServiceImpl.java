package com.zer0drv.blog.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zer0drv.blog.admin.dto.RoleUpdateDTO;
import com.zer0drv.blog.admin.service.AdminUserService;
import com.zer0drv.blog.auth.service.TokenService;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.common.util.JwtSubjects;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.enums.UserRole;
import com.zer0drv.blog.user.service.UserService;
import com.zer0drv.blog.user.vo.UserVO;
import io.github.linpeilie.Converter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * @author Yoruhaki
 */
@Service
@RequiredArgsConstructor
public class AdminUserServiceImpl implements AdminUserService {

    private static final short STATUS_NORMAL = 0;
    private static final short STATUS_BANNED = 1;

    private final UserService userService;
    private final Converter converter;
    private final TokenService tokenService;

    @Override
    public PageResult<UserVO> pageUsers(long page, long size, String keyword, String role, String status) {
        LambdaQueryWrapper<User> wrapper = Wrappers.lambdaQuery(User.class)
                .eq(Objects.nonNull(role) && !role.isBlank(), User::getRole, parseRole(role))
                .eq(Objects.nonNull(status) && !status.isBlank(), User::getStatus, parseStatus(status))
                .orderByDesc(User::getId);
        if (Objects.nonNull(keyword) && !keyword.isBlank()) {
            // keyword 模糊匹配 username / nickname / email
            wrapper.and(w -> w.like(User::getUsername, keyword)
                    .or().like(User::getNickname, keyword)
                    .or().like(User::getEmail, keyword));
        }
        Page<User> result = userService.page(new Page<>(page, size), wrapper);
        List<UserVO> records = result.getRecords().stream()
                .map(user -> converter.convert(user, UserVO.class))
                .toList();
        return PageResult.of(records, result.getTotal(), page, size);
    }

    @Override
    public void ban(Long id, Jwt jwt) {
        User target = requireUser(id);
        assertOperable(target, jwt);
        target.setStatus(STATUS_BANNED);
        userService.updateById(target);
        // #9：封禁立即吊销该用户全部活跃 token，历史 token 即刻 401
        tokenService.blackUserTokens(id);
    }

    @Override
    public void unban(Long id, Jwt jwt) {
        User target = requireUser(id);
        // #6-6：与 ban/updateRole 同一边界校验——不能对 ADMIN 与自己执行 unban
        assertOperable(target, jwt);
        target.setStatus(STATUS_NORMAL);
        userService.updateById(target);
        // #9：解封同样吊销历史 token（封禁前签发的旧 token 不得随解封复活）
        tokenService.blackUserTokens(id);
    }

    @Override
    public void updateRole(Long id, RoleUpdateDTO dto, Jwt jwt) {
        String role = dto.getRole();
        if (!UserRole.isValid(role)) {
            throw new BusinessException(StatusCode.ROLE_CHOICE_ERROR);
        }
        User target = requireUser(id);
        // 边界：不能动现有 ADMIN（含降级）、不能改自己；提拔 ADMIN 已放开——
        // 角色变更必须走 API 以保证吊销事件闭环，不再倒逼库内直改
        assertOperable(target, jwt);
        target.setRole(role);
        userService.updateById(target);
        // #9：角色变更吊销历史 token（旧 token 内 roles 声明已过期），目标用户重新登录领取新角色
        tokenService.blackUserTokens(id);
    }

    /**
     * 操作边界：不能操作 ADMIN、不能操作自己
     */
    private void assertOperable(User target, Jwt jwt) {
        if (UserRole.ADMIN.name().equals(target.getRole())) {
            throw new BusinessException(StatusCode.CANNOT_OPERATE_ADMIN);
        }
        if (JwtSubjects.userIdOf(jwt).equals(target.getId())) {
            throw new BusinessException(StatusCode.CANNOT_OPERATE_SELF);
        }
    }

    private User requireUser(Long id) {
        User user = userService.getById(id);
        if (Objects.isNull(user)) {
            throw new BusinessException(StatusCode.USER_NOT_EXIST);
        }
        return user;
    }

    /**
     * role 筛选值校验：空为不筛选，非法值报角色选择错误
     */
    private String parseRole(String role) {
        if (Objects.isNull(role) || role.isBlank()) {
            return null;
        }
        if (!UserRole.isValid(role)) {
            throw new BusinessException(StatusCode.ROLE_CHOICE_ERROR);
        }
        return role;
    }

    /**
     * status 筛选值校验：仅允许 0/1
     */
    private Short parseStatus(String status) {
        if (Objects.isNull(status) || status.isBlank()) {
            return null;
        }
        if (!"0".equals(status) && !"1".equals(status)) {
            throw new BusinessException(StatusCode.PARAM_INVALID);
        }
        return Short.valueOf(status);
    }
}
