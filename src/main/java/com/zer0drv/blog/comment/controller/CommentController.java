package com.zer0drv.blog.comment.controller;

import com.zer0drv.blog.admin.service.SensitiveWordService;
import com.zer0drv.blog.comment.dto.CommentCreateDTO;
import com.zer0drv.blog.comment.service.CommentService;
import com.zer0drv.blog.comment.vo.CommentVO;
import com.zer0drv.blog.common.captcha.CaptchaService;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.common.util.JwtSubjects;
import com.zer0drv.blog.site.service.SiteConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
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

import java.util.Objects;

/**
 * @author Yoruhaki
 */
@RestController
@RequestMapping("/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;
    private final SensitiveWordService sensitiveWordService;
    private final CaptchaService captchaService;
    /**
     * 站点配置（P0 §2.1 审核开关 message 覆盖）。实现类由 backend-C 提供，
     * 容器中没有实现类时默认关闭兜底，必须 ObjectProvider 注入避免上下文启动失败
     */
    private final ObjectProvider<SiteConfigService> siteConfigServiceProvider;

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
        // 业务执行前校验图形验证码（未达阈值直接放行），评论按当前用户 ID 计数
        String userId = String.valueOf(JwtSubjects.userIdOf(jwt));
        captchaService.verify(CaptchaService.SCENE_COMMENT, userId, dto.getCaptchaId(), dto.getCaptchaCode());
        captchaService.recordAttempt(CaptchaService.SCENE_COMMENT, userId);
        Result<Long> result = Result.ok(commentService.create(dto, jwt));
        // M5：命中敏感词的评论以 FOLDED 落库进入审核，接口正常返回但 message 覆盖提示（前端按 message 提示）
        if (sensitiveWordService.containsSensitiveWord(dto.getContent())) {
            result.setMessage("包含敏感内容，已进入审核");
        } else if (isCommentReviewRequired()) {
            // P0 §2.1：审核开关开启时评论以 PENDING 落库，公开列表不可见，message 覆盖提示
            result.setMessage("评论已提交，审核通过后展示");
        }
        return result;
    }

    /**
     * P0 §2.1 评论审核开关：SiteConfigService 实现缺失时默认关闭（兼容 IT 与合并前环境）
     */
    private boolean isCommentReviewRequired() {
        SiteConfigService siteConfigService = siteConfigServiceProvider.getIfAvailable();
        return Objects.nonNull(siteConfigService)
                && siteConfigService.getBool("comment.review_required", false);
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
