package com.zer0drv.blog.attachment.controller;

import com.zer0drv.blog.attachment.dto.AttachmentGroupSaveDTO;
import com.zer0drv.blog.attachment.dto.AttachmentMoveDTO;
import com.zer0drv.blog.attachment.service.AttachmentService;
import com.zer0drv.blog.attachment.vo.AttachmentGroupVO;
import com.zer0drv.blog.attachment.vo.AttachmentVO;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.common.util.JwtSubjects;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 附件库（登录即可，只能操作自己的数据；他人数据一律按不存在处理）。
 *
 * @author Yoruhaki
 */
@RestController
@RequestMapping("/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;

    /**
     * 本人附件分页（createTime 倒序；keyword 匹配文件名；groupId 可空=全部）
     */
    @GetMapping
    public Result<PageResult<AttachmentVO>> page(@AuthenticationPrincipal Jwt jwt,
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "24") long size,
                                                 @RequestParam(required = false) Long groupId,
                                                 @RequestParam(required = false) String keyword) {
        return Result.ok(attachmentService.pageMine(JwtSubjects.userIdOf(jwt), page, size, groupId, keyword));
    }

    /**
     * 本人分组列表（含组内附件数）
     */
    @GetMapping("/groups")
    public Result<List<AttachmentGroupVO>> groups(@AuthenticationPrincipal Jwt jwt) {
        return Result.ok(attachmentService.listGroups(JwtSubjects.userIdOf(jwt)));
    }

    /**
     * 新建分组
     */
    @PostMapping("/groups")
    public Result<Long> createGroup(@AuthenticationPrincipal Jwt jwt,
                                    @RequestBody @Valid AttachmentGroupSaveDTO dto) {
        return Result.ok(attachmentService.createGroup(JwtSubjects.userIdOf(jwt), dto.getName()));
    }

    /**
     * 重命名分组
     */
    @PutMapping("/groups/{id}")
    public Result<Void> renameGroup(@AuthenticationPrincipal Jwt jwt,
                                    @PathVariable Long id,
                                    @RequestBody @Valid AttachmentGroupSaveDTO dto) {
        attachmentService.renameGroup(JwtSubjects.userIdOf(jwt), id, dto.getName());
        return Result.ok();
    }

    /**
     * 删除分组（组内有附件 → 40071；空组逻辑删除）
     */
    @DeleteMapping("/groups/{id}")
    public Result<Void> deleteGroup(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        attachmentService.deleteGroup(JwtSubjects.userIdOf(jwt), id);
        return Result.ok();
    }

    /**
     * 移入分组（groupId 可空=移出）
     */
    @PutMapping("/{id}")
    public Result<Void> move(@AuthenticationPrincipal Jwt jwt,
                             @PathVariable Long id,
                             @RequestBody AttachmentMoveDTO dto) {
        attachmentService.moveToGroup(JwtSubjects.userIdOf(jwt), id, dto.getGroupId());
        return Result.ok();
    }

    /**
     * 删除附件（只逻辑删记录，不删存储对象）
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        attachmentService.deleteMine(JwtSubjects.userIdOf(jwt), id);
        return Result.ok();
    }
}
