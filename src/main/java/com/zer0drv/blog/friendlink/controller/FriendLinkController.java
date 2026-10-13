package com.zer0drv.blog.friendlink.controller;

import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.friendlink.service.FriendLinkService;
import com.zer0drv.blog.friendlink.vo.FriendLinkVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 友情链接公开接口（issue #17，匿名可读，SecurityConfig 已 permitAll GET /friend-links）。
 * 只出启用状态记录，sort 升序。
 *
 * @author Yoruhaki
 */
@RestController
@RequestMapping("/friend-links")
@RequiredArgsConstructor
public class FriendLinkController {

    private final FriendLinkService friendLinkService;

    /**
     * 公开友链列表（仅启用，sort 升序）
     */
    @GetMapping
    public Result<List<FriendLinkVO>> list() {
        return Result.ok(friendLinkService.listPublic());
    }
}
