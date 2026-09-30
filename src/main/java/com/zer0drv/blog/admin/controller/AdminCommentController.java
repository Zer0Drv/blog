package com.zer0drv.blog.admin.controller;

import com.zer0drv.blog.admin.service.AdminCommentService;
import com.zer0drv.blog.admin.vo.AdminCommentVO;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评论治理（M5）。鉴权由 SecurityConfig 的 /admin/** hasRole(ADMIN) 覆盖，无需 @PreAuthorize。
 *
 * @author Yoruhaki
 */
@RestController
@RequestMapping("/admin/comments")
@RequiredArgsConstructor
public class AdminCommentController {

    private final AdminCommentService adminCommentService;

    /**
     * 评论分页（含 FOLDED/PENDING；status/keyword/articleId 可空筛选；status=TRASH 查回收站）
     */
    @GetMapping
    public Result<PageResult<AdminCommentVO>> pageComments(@RequestParam(defaultValue = "1") long page,
                                                           @RequestParam(defaultValue = "10") long size,
                                                           @RequestParam(required = false) String status,
                                                           @RequestParam(required = false) String keyword,
                                                           @RequestParam(required = false) Long articleId) {
        return Result.ok(adminCommentService.pageComments(page, size, status, keyword, articleId));
    }

    /**
     * 折叠（status=FOLDED，前台不再展示）
     */
    @PutMapping("/{id}/fold")
    public Result<Void> fold(@PathVariable Long id) {
        adminCommentService.fold(id);
        return Result.ok();
    }

    /**
     * 恢复（status=NORMAL）
     */
    @PutMapping("/{id}/unfold")
    public Result<Void> unfold(@PathVariable Long id) {
        adminCommentService.unfold(id);
        return Result.ok();
    }

    /**
     * 删除（逻辑删除，删主评论连带其回复）
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        adminCommentService.delete(id);
        return Result.ok();
    }

    /**
     * 审核通过（P0 §2.1）：仅 PENDING → NORMAL，成功后补发评论通知（含邮件）
     */
    @PutMapping("/{id}/approve")
    public Result<Void> approve(@PathVariable Long id) {
        adminCommentService.approve(id);
        return Result.ok();
    }

    /**
     * 审核拒绝（P0 §2.1）：仅 PENDING → FOLDED（不通知）
     */
    @PutMapping("/{id}/reject")
    public Result<Void> reject(@PathVariable Long id) {
        adminCommentService.reject(id);
        return Result.ok();
    }

    /**
     * 回收站恢复（P0 §2.4）：deleted=1 → deleted=0、status=NORMAL；主评论连带恢复其回复
     */
    @PostMapping("/{id}/restore")
    public Result<Void> restore(@PathVariable Long id) {
        adminCommentService.restore(id);
        return Result.ok();
    }

    /**
     * 彻底删除（P0 §2.4）：物理删除；主评论连带物理删全部回复与评论点赞关联
     */
    @DeleteMapping("/{id}/force")
    public Result<Void> forceDelete(@PathVariable Long id) {
        adminCommentService.forceDelete(id);
        return Result.ok();
    }
}
