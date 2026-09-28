package com.zer0drv.blog.admin.service;

import com.zer0drv.blog.admin.dto.RoleUpdateDTO;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.user.vo.UserVO;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 用户管理（M5，仅 ADMIN，由 SecurityConfig /admin/** 保护）。
 *
 * @author Yoruhaki
 */
public interface AdminUserService {

    /**
     * 用户分页。keyword 模糊匹配 username/nickname/email；role（ADMIN/AUTHOR/USER）、status（0/1）可空筛选。
     */
    PageResult<UserVO> pageUsers(long page, long size, String keyword, String role, String status);

    /**
     * 封禁（status=1）。不能封 ADMIN（40061）、不能封自己（40062）
     */
    void ban(Long id, Jwt jwt);

    /**
     * 解封（status=0）
     */
    void unban(Long id, Jwt jwt);

    /**
     * 角色变更：仅允许 USER↔AUTHOR。不允许改成 ADMIN、不允许操作 ADMIN、不允许改自己。
     */
    void updateRole(Long id, RoleUpdateDTO dto, Jwt jwt);
}
