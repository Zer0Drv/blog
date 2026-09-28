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
     * 评论分页（含 FOLDED）。status（NORMAL/FOLDED）、keyword（模糊匹配内容）、articleId 均可空。
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
}
