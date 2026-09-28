package com.zer0drv.blog.interaction.controller;

import com.zer0drv.blog.article.vo.ArticleListVO;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.interaction.service.InteractionService;
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
 * 互动：文章点赞 / 收藏、评论点赞、我的收藏（全部需登录）。
 *
 * @author Yoruhaki
 */
@RestController
@RequiredArgsConstructor
public class InteractionController {

    private final InteractionService interactionService;

    /**
     * 文章点赞（幂等）
     */
    @PostMapping("/articles/{id}/like")
    @PreAuthorize("isAuthenticated()")
    public Result<Void> likeArticle(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        interactionService.likeArticle(id, jwt);
        return Result.ok();
    }

    /**
     * 取消文章点赞
     */
    @DeleteMapping("/articles/{id}/like")
    @PreAuthorize("isAuthenticated()")
    public Result<Void> unlikeArticle(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        interactionService.unlikeArticle(id, jwt);
        return Result.ok();
    }

    /**
     * 文章收藏（幂等）
     */
    @PostMapping("/articles/{id}/favorite")
    @PreAuthorize("isAuthenticated()")
    public Result<Void> favoriteArticle(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        interactionService.favoriteArticle(id, jwt);
        return Result.ok();
    }

    /**
     * 取消文章收藏
     */
    @DeleteMapping("/articles/{id}/favorite")
    @PreAuthorize("isAuthenticated()")
    public Result<Void> unfavoriteArticle(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        interactionService.unfavoriteArticle(id, jwt);
        return Result.ok();
    }

    /**
     * 评论点赞（幂等，维护 comment.like_count）
     */
    @PostMapping("/comments/{id}/like")
    @PreAuthorize("isAuthenticated()")
    public Result<Void> likeComment(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        interactionService.likeComment(id, jwt);
        return Result.ok();
    }

    /**
     * 取消评论点赞
     */
    @DeleteMapping("/comments/{id}/like")
    @PreAuthorize("isAuthenticated()")
    public Result<Void> unlikeComment(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        interactionService.unlikeComment(id, jwt);
        return Result.ok();
    }

    /**
     * 我的收藏文章分页（按收藏时间倒序）
     */
    @GetMapping("/articles/mine/favorites")
    @PreAuthorize("isAuthenticated()")
    public Result<PageResult<ArticleListVO>> pageMyFavorites(@RequestParam(defaultValue = "1") long page,
                                                             @RequestParam(defaultValue = "10") long size,
                                                             @AuthenticationPrincipal Jwt jwt) {
        return Result.ok(interactionService.pageMyFavorites(page, size, jwt));
    }
}