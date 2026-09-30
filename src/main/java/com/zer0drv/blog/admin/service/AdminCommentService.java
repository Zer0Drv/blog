package com.zer0drv.blog.admin.service;

import com.zer0drv.blog.admin.vo.AdminCommentVO;
import com.zer0drv.blog.common.response.PageResult;

/**
 * 评论治理（M5，仅 ADMIN，由 SecurityConfig /admin/** 保护）。
 *
 * @author Yoruhaki
 */
public interface AdminCommentService {

    /**
     * 评论分页（含 FOLDED/PENDING）。status（NORMAL/FOLDED/PENDING，或伪状态 TRASH=回收站）、
     * keyword（模糊匹配内容）、articleId 均可空。
     */
    PageResult<AdminCommentVO> pageComments(long page, long size, String status, String keyword, Long articleId);

    /**
     * 折叠（status=FOLDED，前台不再展示）
     */
    void fold(Long id);

    /**
     * 恢复（status=NORMAL）
     */
    void unfold(Long id);

    /**
     * 删除（逻辑删除，删主评论连带其回复）
     */
    void delete(Long id);

    /**
     * 审核通过（P0 §2.1）：仅 PENDING → NORMAL，成功后补发评论通知（含邮件）；
     * 非 PENDING → PARAM_INVALID「仅待审核评论可执行该操作」
     */
    void approve(Long id);

    /**
     * 审核拒绝（P0 §2.1）：仅 PENDING → FOLDED（不通知）；非 PENDING → 同 approve 的 PARAM_INVALID
     */
    void reject(Long id);

    /**
     * 回收站恢复（P0 §2.4）：deleted=1 → deleted=0、status=NORMAL；
     * 主评论连带恢复其 deleted=1 的回复（近似，见实现注释）。不存在 → COMMENT_NOT_EXIST
     */
    void restore(Long id);

    /**
     * 彻底删除（P0 §2.4）：物理删除该评论；主评论连带物理删全部回复与评论点赞关联。
     * 不存在（含回收站中的）→ COMMENT_NOT_EXIST
     */
    void forceDelete(Long id);
}
