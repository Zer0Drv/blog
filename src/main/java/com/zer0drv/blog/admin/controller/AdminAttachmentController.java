package com.zer0drv.blog.admin.controller;

import com.zer0drv.blog.admin.vo.AdminAttachmentVO;
import com.zer0drv.blog.attachment.service.AttachmentService;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 附件库管理（全量查询 + 逻辑删）。鉴权由 SecurityConfig 的 /admin/** hasRole(ADMIN) 覆盖。
 *
 * @author Yoruhaki
 */
@RestController
@RequestMapping("/admin/attachments")
@RequiredArgsConstructor
public class AdminAttachmentController {

    private final AttachmentService attachmentService;

    /**
     * 全量附件分页（可按上传者 userId / 文件名 keyword 过滤）
     */
    @GetMapping
    public Result<PageResult<AdminAttachmentVO>> page(@RequestParam(defaultValue = "1") long page,
                                                      @RequestParam(defaultValue = "24") long size,
                                                      @RequestParam(required = false) Long userId,
                                                      @RequestParam(required = false) String keyword) {
        return Result.ok(attachmentService.pageAll(page, size, userId, keyword));
    }

    /**
     * 删除附件（不限属主；逻辑删记录 + 物理删存储对象，物理删失败只告警）
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        attachmentService.deleteByAdmin(id);
        return Result.ok();
    }
}
