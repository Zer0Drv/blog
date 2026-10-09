package com.zer0drv.blog.attachment.service;

import com.zer0drv.blog.admin.vo.AdminAttachmentVO;
import com.zer0drv.blog.attachment.domain.Attachment;
import com.zer0drv.blog.attachment.vo.AttachmentGroupVO;
import com.zer0drv.blog.attachment.vo.AttachmentVO;
import com.zer0drv.blog.common.response.PageResult;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 附件库服务（用户侧只能操作自己的数据；管理端全量查询/逻辑删）
 *
 * @author Yoruhaki
 */
public interface AttachmentService {

    /**
     * 上传成功后落一条附件记录，返回带 id 的实体
     */
    Attachment recordUpload(Long userId, MultipartFile file, String url);

    /**
     * 本人附件分页（createTime 倒序；keyword 匹配 filename；groupId 可空=全部）
     */
    PageResult<AttachmentVO> pageMine(Long userId, long page, long size, Long groupId, String keyword);

    /**
     * 本人分组列表（含组内附件数）
     */
    List<AttachmentGroupVO> listGroups(Long userId);

    /**
     * 新建分组，返回分组id
     */
    Long createGroup(Long userId, String name);

    /**
     * 重命名分组（须属本人）
     */
    void renameGroup(Long userId, Long groupId, String name);

    /**
     * 删除分组（组内有附件 → ATTACHMENT_GROUP_HAS_ITEMS；空组逻辑删除）
     */
    void deleteGroup(Long userId, Long groupId);

    /**
     * 附件移入分组（groupId 可空=移出；附件与分组均须属本人）
     */
    void moveToGroup(Long userId, Long attachmentId, Long groupId);

    /**
     * 删除本人附件（逻辑删记录 + 物理删存储对象，物理删失败只告警）
     */
    void deleteMine(Long userId, Long attachmentId);

    /**
     * 管理端全量分页（可按上传者/文件名过滤）
     */
    PageResult<AdminAttachmentVO> pageAll(long page, long size, Long userId, String keyword);

    /**
     * 管理端删除（不限属主；逻辑删记录 + 物理删存储对象，物理删失败只告警）
     */
    void deleteByAdmin(Long attachmentId);
}
