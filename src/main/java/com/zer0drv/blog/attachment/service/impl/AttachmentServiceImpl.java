package com.zer0drv.blog.attachment.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zer0drv.blog.admin.vo.AdminAttachmentVO;
import com.zer0drv.blog.attachment.domain.Attachment;
import com.zer0drv.blog.attachment.domain.AttachmentGroup;
import com.zer0drv.blog.attachment.mapper.AttachmentGroupMapper;
import com.zer0drv.blog.attachment.mapper.AttachmentMapper;
import com.zer0drv.blog.attachment.service.AttachmentService;
import com.zer0drv.blog.attachment.vo.AttachmentGroupVO;
import com.zer0drv.blog.attachment.vo.AttachmentVO;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.StatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Objects;

/**
 * 附件库实现。越权语义：他人数据一律按「不存在」处理（不暴露存在性）。
 * 删除附件只逻辑删记录、不删存储对象（MinIO/本地磁盘的对象清理由 P1 统一做）。
 *
 * @author Yoruhaki
 */
@Service
@RequiredArgsConstructor
public class AttachmentServiceImpl implements AttachmentService {

    private final AttachmentMapper attachmentMapper;
    private final AttachmentGroupMapper attachmentGroupMapper;

    @Override
    public Attachment recordUpload(Long userId, MultipartFile file, String url) {
        Attachment attachment = new Attachment();
        attachment.setUserId(userId);
        attachment.setGroupId(null);
        attachment.setUrl(url);
        attachment.setObjectKey(resolveObjectKey(url));
        // MinIO 返回绝对 URL（{publicUrl}/{bucket}/{object}），本地 fallback 返回 /uploads/** 相对路径
        attachment.setStorage(url.startsWith("http") ? Attachment.STORAGE_MINIO : Attachment.STORAGE_LOCAL);
        attachment.setFilename(Objects.nonNull(file.getOriginalFilename()) ? file.getOriginalFilename() : "");
        attachment.setMediaType(Objects.nonNull(file.getContentType()) ? file.getContentType() : "");
        attachment.setSizeBytes(file.getSize());
        attachmentMapper.insert(attachment);
        return attachment;
    }

    /**
     * 从 URL 解析存储对象名：取末两段（yyyyMM/uuid.ext）
     */
    private String resolveObjectKey(String url) {
        String[] segments = url.split("/");
        if (segments.length >= 2) {
            return segments[segments.length - 2] + "/" + segments[segments.length - 1];
        }
        return url;
    }

    @Override
    public PageResult<AttachmentVO> pageMine(Long userId, long page, long size, Long groupId, String keyword) {
        Page<Attachment> result = attachmentMapper.selectPage(new Page<>(page, size),
                Wrappers.lambdaQuery(Attachment.class)
                        .eq(Attachment::getUserId, userId)
                        .eq(Objects.nonNull(groupId), Attachment::getGroupId, groupId)
                        .like(Objects.nonNull(keyword) && !keyword.isBlank(), Attachment::getFilename, keyword)
                        .orderByDesc(Attachment::getCreateTime));
        List<AttachmentVO> records = result.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(records, result.getTotal(), page, size);
    }

    @Override
    public List<AttachmentGroupVO> listGroups(Long userId) {
        List<AttachmentGroup> groups = attachmentGroupMapper.selectList(Wrappers.lambdaQuery(AttachmentGroup.class)
                .eq(AttachmentGroup::getUserId, userId)
                .orderByAsc(AttachmentGroup::getSort)
                .orderByAsc(AttachmentGroup::getId));
        return groups.stream().map(group -> {
            AttachmentGroupVO vo = new AttachmentGroupVO();
            vo.setId(group.getId());
            vo.setName(group.getName());
            vo.setSort(group.getSort());
            // 组内附件计数（个人博客量级，逐组 count 即可）
            vo.setCount(attachmentMapper.selectCount(Wrappers.lambdaQuery(Attachment.class)
                    .eq(Attachment::getGroupId, group.getId())));
            return vo;
        }).toList();
    }

    @Override
    public Long createGroup(Long userId, String name) {
        AttachmentGroup group = new AttachmentGroup();
        group.setUserId(userId);
        group.setName(name.trim());
        group.setSort(0);
        attachmentGroupMapper.insert(group);
        return group.getId();
    }

    @Override
    public void renameGroup(Long userId, Long groupId, String name) {
        AttachmentGroup group = getOwnGroup(userId, groupId);
        group.setName(name.trim());
        attachmentGroupMapper.updateById(group);
    }

    @Override
    public void deleteGroup(Long userId, Long groupId) {
        AttachmentGroup group = getOwnGroup(userId, groupId);
        long items = attachmentMapper.selectCount(Wrappers.lambdaQuery(Attachment.class)
                .eq(Attachment::getGroupId, group.getId()));
        if (items > 0) {
            throw new BusinessException(StatusCode.ATTACHMENT_GROUP_HAS_ITEMS);
        }
        // 空组逻辑删除（MP 全局逻辑删除配置：deleteById 实际置 deleted=1）
        attachmentGroupMapper.deleteById(group.getId());
    }

    @Override
    public void moveToGroup(Long userId, Long attachmentId, Long groupId) {
        Attachment attachment = getOwnAttachment(userId, attachmentId);
        if (Objects.nonNull(groupId)) {
            // 目标分组须存在且属本人
            getOwnGroup(userId, groupId);
        }
        // 注意不能用 updateById：MP 默认字段策略跳过 null，groupId=null（移出分组）不会落 SQL，
        // 必须用 UpdateWrapper 显式 set
        attachmentMapper.update(null, Wrappers.lambdaUpdate(Attachment.class)
                .eq(Attachment::getId, attachment.getId())
                .set(Attachment::getGroupId, groupId));
    }

    @Override
    public void deleteMine(Long userId, Long attachmentId) {
        Attachment attachment = getOwnAttachment(userId, attachmentId);
        doLogicDelete(attachment.getId());
    }

    @Override
    public PageResult<AdminAttachmentVO> pageAll(long page, long size, Long userId, String keyword) {
        Page<Attachment> result = attachmentMapper.selectPage(new Page<>(page, size),
                Wrappers.lambdaQuery(Attachment.class)
                        .eq(Objects.nonNull(userId), Attachment::getUserId, userId)
                        .like(Objects.nonNull(keyword) && !keyword.isBlank(), Attachment::getFilename, keyword)
                        .orderByDesc(Attachment::getCreateTime));
        List<AdminAttachmentVO> records = result.getRecords().stream().map(attachment -> {
            AdminAttachmentVO vo = new AdminAttachmentVO();
            copyToVO(attachment, vo);
            vo.setUserId(attachment.getUserId());
            return vo;
        }).toList();
        return PageResult.of(records, result.getTotal(), page, size);
    }

    @Override
    public void deleteByAdmin(Long attachmentId) {
        Attachment attachment = attachmentMapper.selectById(attachmentId);
        if (Objects.isNull(attachment)) {
            throw new BusinessException(StatusCode.ATTACHMENT_NOT_EXIST);
        }
        doLogicDelete(attachment.getId());
    }

    /**
     * 逻辑删除附件记录。注意：只删记录，不删 MinIO/本地磁盘上的存储对象
     * （对象物理清理由 P1 的统一清理任务处理，避免误删被历史文章引用的图）。
     */
    private void doLogicDelete(Long attachmentId) {
        attachmentMapper.deleteById(attachmentId);
    }

    /**
     * 取本人附件；不存在或属他人 → ATTACHMENT_NOT_EXIST（不暴露存在性）
     */
    private Attachment getOwnAttachment(Long userId, Long attachmentId) {
        Attachment attachment = attachmentMapper.selectById(attachmentId);
        if (Objects.isNull(attachment) || !Objects.equals(attachment.getUserId(), userId)) {
            throw new BusinessException(StatusCode.ATTACHMENT_NOT_EXIST);
        }
        return attachment;
    }

    /**
     * 取本人分组；不存在或属他人 → ATTACHMENT_GROUP_NOT_EXIST（不暴露存在性）
     */
    private AttachmentGroup getOwnGroup(Long userId, Long groupId) {
        AttachmentGroup group = attachmentGroupMapper.selectById(groupId);
        if (Objects.isNull(group) || !Objects.equals(group.getUserId(), userId)) {
            throw new BusinessException(StatusCode.ATTACHMENT_GROUP_NOT_EXIST);
        }
        return group;
    }

    private AttachmentVO toVO(Attachment attachment) {
        AttachmentVO vo = new AttachmentVO();
        copyToVO(attachment, vo);
        return vo;
    }

    private void copyToVO(Attachment attachment, AttachmentVO vo) {
        vo.setId(attachment.getId());
        vo.setUrl(attachment.getUrl());
        vo.setObjectKey(attachment.getObjectKey());
        vo.setStorage(attachment.getStorage());
        vo.setFilename(attachment.getFilename());
        vo.setMediaType(attachment.getMediaType());
        vo.setSizeBytes(attachment.getSizeBytes());
        vo.setGroupId(attachment.getGroupId());
        vo.setCreateTime(attachment.getCreateTime());
    }
}
