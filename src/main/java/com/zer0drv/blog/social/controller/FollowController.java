package com.zer0drv.blog.social.controller;

import com.zer0drv.blog.article.vo.ArticleListVO;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.social.service.FollowService;
import com.zer0drv.blog.social.vo.FollowUserVO;
import com.zer0drv.blog.social.vo.UserProfileVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 关注 / 用户公开主页 / 关注动态 Feed。
 * /users/** 的 GET 接口公开；关注、取关、Feed 需登录。
 *
 * @author Yoruhaki
 */
@RestController
@RequiredArgsConstructor
public class FollowController {

    private final FollowService followService;

    /**
     * 关注（幂等；不能关注自己）
     */
    @PostMapping("/users/{id}/follow")
    @PreAuthorize("isAuthenticated()")
    public Result<Void> follow(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        followService.follow(id, jwt);
        return Result.ok();
    }

    /**
     * 取关（物理删除）
     */
    @DeleteMapping("/users/{id}/follow")
    @PreAuthorize("isAuthenticated()")
    public Result<Void> unfollow(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        followService.unfollow(id, jwt);
        return Result.ok();
    }

    /**
     * 粉丝分页（公开）
     */
    @GetMapping("/users/{id}/followers")
    public Result<PageResult<FollowUserVO>> pageFollowers(@PathVariable Long id,
                                                          @RequestParam(defaultValue = "1") long page,
                                                          @RequestParam(defaultValue = "10") long size,
                                                          @AuthenticationPrincipal Jwt jwt) {
        return Result.ok(followService.pageFollowers(id, page, size, jwt));
    }

    /**
     * 关注分页（公开）
     */
    @GetMapping("/users/{id}/following")
    public Result<PageResult<FollowUserVO>> pageFollowing(@PathVariable Long id,
                                                          @RequestParam(defaultValue = "1") long page,
                                                          @RequestParam(defaultValue = "10") long size,
                                                          @AuthenticationPrincipal Jwt jwt) {
        return Result.ok(followService.pageFollowing(id, page, size, jwt));
    }

    /**
     * 用户公开主页信息（公开；followed 为当前登录者是否已关注）
     */
    @GetMapping("/users/{id}/profile")
    public Result<UserProfileVO> profile(@PathVariable Long id,
                                         @AuthenticationPrincipal Jwt jwt) {
        return Result.ok(followService.profile(id, jwt));
    }

    /**
     * 某用户的 PUBLISHED 文章分页（公开，publish_time 倒序）
     */
    @GetMapping("/users/{id}/articles")
    public Result<PageResult<ArticleListVO>> pageUserArticles(@PathVariable Long id,
                                                              @RequestParam(defaultValue = "1") long page,
                                                              @RequestParam(defaultValue = "10") long size) {
        return Result.ok(followService.pageUserArticles(id, page, size));
    }

    /**
     * 关注动态 Feed（登录）：关注作者的 PUBLISHED 文章分页，publish_time 倒序
     */
    @GetMapping("/feed")
    @PreAuthorize("isAuthenticated()")
    public Result<PageResult<ArticleListVO>> pageFeed(@RequestParam(defaultValue = "1") long page,
                                                      @RequestParam(defaultValue = "10") long size,
                                                      @AuthenticationPrincipal Jwt jwt) {
        return Result.ok(followService.pageFeed(page, size, jwt));
    }
}
