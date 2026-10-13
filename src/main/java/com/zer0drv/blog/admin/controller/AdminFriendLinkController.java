package com.zer0drv.blog.admin.controller;

import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.friendlink.dto.FriendLinkSaveDTO;
import com.zer0drv.blog.friendlink.dto.FriendLinkStatusDTO;
import com.zer0drv.blog.friendlink.service.FriendLinkService;
import com.zer0drv.blog.friendlink.vo.FriendLinkVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 友情链接管理（issue #17）。鉴权由 SecurityConfig 的 /admin/** hasRole(ADMIN) 覆盖。
 * 跨模块仅依赖 friendlink 的 service 契约接口与 dto/vo（与 AdminSiteController 依赖
 * site.service.SiteConfigService 同款模式），不触碰 friendlink 内部包。
 *
 * @author Yoruhaki
 */
@RestController
@RequestMapping("/admin/friend-links")
@RequiredArgsConstructor
public class AdminFriendLinkController {

    private final FriendLinkService friendLinkService;

    /**
     * 友链分页（keyword 模糊匹配名称/URL；status 可选过滤）
     */
    @GetMapping
    public Result<PageResult<FriendLinkVO>> page(@RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long size,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) Short status) {
        return Result.ok(friendLinkService.page(page, size, keyword, status));
    }

    /**
     * 新增友链（默认启用）
     */
    @PostMapping
    public Result<Long> add(@RequestBody @Valid FriendLinkSaveDTO dto) {
        return Result.ok(friendLinkService.add(dto));
    }

    /**
     * 编辑友链
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody @Valid FriendLinkSaveDTO dto) {
        friendLinkService.update(id, dto);
        return Result.ok();
    }

    /**
     * 启用/停用友链
     */
    @PutMapping("/{id}/status")
    public Result<Void> setStatus(@PathVariable Long id, @RequestBody @Valid FriendLinkStatusDTO dto) {
        friendLinkService.setStatus(id, dto.getStatus());
        return Result.ok();
    }

    /**
     * 删除友链（逻辑删除）
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        friendLinkService.delete(id);
        return Result.ok();
    }
}
