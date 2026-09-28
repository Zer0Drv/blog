package com.zer0drv.blog.comment.controller;

import com.zer0drv.blog.comment.dto.CommentCreateDTO;
import com.zer0drv.blog.comment.service.CommentService;
import com.zer0drv.blog.comment.vo.CommentVO;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author Yoruhaki
 */
@RestController
@RequestMapping("/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    /**
     * 主评论分页（公开）。sort：time_desc(默认) / time_asc / hot；
     * 每条主评论内嵌前 3 条回复（时间正序）+ replyCount。
     */
    @GetMapping
    public Result<PageResult<CommentVO>> pageRootComments(@RequestParam Long articleId,
                                                          @RequestParam(required = false) String sort,
                                                          @RequestParam(defaultValue = "1") long page,
                                                          @RequestParam(defaultValue = "10") long size,
                                                          @AuthenticationPrincipal Jwt jwt) {
        return Result.ok(commentService.pageRootComments(articleId, sort, page, size, jwt));
    }

    /**
     * 某主评论的全部回复分页（公开，时间正序）
     */
    @GetMapping("/{rootId}/replies")
    public Result<PageResult<CommentVO>> pageReplies(@PathVariable Long rootId,
                                                     @RequestParam(defaultValue = "1") long page,
                                                     @RequestParam(defaultValue = "10") long size,
                                                     @AuthenticationPrincipal Jwt jwt) {
        return Result.ok(commentService.pageReplies(rootId, page, size, jwt));
    }

    /**
     * 发表评论（登录）。parentId 为空 = 主评论；指向二级评论时归一化到其 root。
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public Result<Long> create(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid CommentCreateDTO dto) {
        return Result.ok(commentService.create(dto, jwt));
    }

    /**
     * 删除评论（仅本人或 ADMIN；删主评论连带逻辑删除其回复）
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public Result<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        commentService.delete(id, jwt);
        return Result.ok();
    }
}