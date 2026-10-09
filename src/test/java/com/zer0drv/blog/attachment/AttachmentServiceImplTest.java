package com.zer0drv.blog.attachment;

import com.zer0drv.blog.attachment.domain.Attachment;
import com.zer0drv.blog.attachment.domain.AttachmentGroup;
import com.zer0drv.blog.attachment.mapper.AttachmentGroupMapper;
import com.zer0drv.blog.attachment.mapper.AttachmentMapper;
import com.zer0drv.blog.attachment.service.impl.AttachmentServiceImpl;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.upload.service.UploadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AttachmentServiceImpl 纯单测：本人数据边界（越权=不存在）、分组删除保护、上传落库字段。
 *
 * @author Yoruhaki
 */
@ExtendWith(MockitoExtension.class)
class AttachmentServiceImplTest {

    @Mock
    private AttachmentMapper attachmentMapper;

    @Mock
    private AttachmentGroupMapper attachmentGroupMapper;

    @Mock
    private UploadService uploadService;

    @InjectMocks
    private AttachmentServiceImpl attachmentService;

    /**
     * MP lambda 缓存初始化：纯 Mockito 单测无 MP 上下文，
     * 否则 LambdaQueryWrapper/LambdaUpdateWrapper(实体::字段) 找不到 lambda 列缓存
     * （参照 SensitiveWordServiceImplTest / ArticleServiceImplTest；本地偶绿是测试序副作用，CI 必现）
     */
    @org.junit.jupiter.api.BeforeAll
    static void initLambdaCache() {
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, Attachment.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, AttachmentGroup.class);
    }

    private static Attachment attachment(long id, long userId) {
        Attachment attachment = new Attachment();
        attachment.setId(id);
        attachment.setUserId(userId);
        return attachment;
    }

    private static AttachmentGroup group(long id, long userId) {
        AttachmentGroup group = new AttachmentGroup();
        group.setId(id);
        group.setUserId(userId);
        group.setName("分组");
        return group;
    }

    private static void assertCode(StatusCode expected, BusinessException e) {
        assertEquals(expected.getCode(), e.getCode());
    }

    // ---------- 上传落库 ----------

    @Test
    void recordUploadParsesLocalUrl() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("封面.png");
        when(file.getContentType()).thenReturn("image/png");
        when(file.getSize()).thenReturn(12345L);

        attachmentService.recordUpload(7L, file, "/uploads/202501/uuid.png");

        ArgumentCaptor<Attachment> captor = ArgumentCaptor.forClass(Attachment.class);
        verify(attachmentMapper).insert(captor.capture());
        Attachment saved = captor.getValue();
        assertEquals(7L, saved.getUserId());
        assertNull(saved.getGroupId());
        assertEquals("202501/uuid.png", saved.getObjectKey());
        assertEquals(Attachment.STORAGE_LOCAL, saved.getStorage());
        assertEquals("封面.png", saved.getFilename());
        assertEquals("image/png", saved.getMediaType());
        assertEquals(12345L, saved.getSizeBytes());
    }

    @Test
    void recordUploadDetectsMinioAbsoluteUrl() {
        MultipartFile file = mock(MultipartFile.class);
        attachmentService.recordUpload(7L, file, "http://localhost:9000/blog-images/202501/uuid.png");
        ArgumentCaptor<Attachment> captor = ArgumentCaptor.forClass(Attachment.class);
        verify(attachmentMapper).insert(captor.capture());
        assertEquals(Attachment.STORAGE_MINIO, captor.getValue().getStorage());
        assertEquals("202501/uuid.png", captor.getValue().getObjectKey());
    }

    // ---------- 附件删除（逻辑删 + 物理删存储对象） ----------

    @Test
    void deleteMineRemovesStorageObject() {
        Attachment attachment = attachment(1L, 7L);
        attachment.setObjectKey("202501/uuid.png");
        when(attachmentMapper.selectById(1L)).thenReturn(attachment);

        attachmentService.deleteMine(7L, 1L);

        verify(attachmentMapper).deleteById(1L);
        // 逻辑删后物理删存储对象（失败容忍由 UploadService.deleteObject 保证）
        verify(uploadService).deleteObject("202501/uuid.png");
    }

    @Test
    void deleteByAdminRemovesStorageObject() {
        Attachment attachment = attachment(1L, 99L);
        attachment.setObjectKey("202502/uuid2.png");
        when(attachmentMapper.selectById(1L)).thenReturn(attachment);

        attachmentService.deleteByAdmin(1L);

        verify(attachmentMapper).deleteById(1L);
        verify(uploadService).deleteObject("202502/uuid2.png");
    }

    @Test
    void deleteNonExistentSkipsStorageCleanup() {
        when(attachmentMapper.selectById(1L)).thenReturn(null);

        assertCode(StatusCode.ATTACHMENT_NOT_EXIST, assertThrows(BusinessException.class,
                () -> attachmentService.deleteByAdmin(1L)));
        verify(attachmentMapper, never()).deleteById(any(Long.class));
        verify(uploadService, never()).deleteObject(any());
    }

    // ---------- 分组删除保护 ----------

    @Test
    void deleteGroupWithItemsRejected() {
        when(attachmentGroupMapper.selectById(1L)).thenReturn(group(1L, 7L));
        when(attachmentMapper.selectCount(any())).thenReturn(2L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> attachmentService.deleteGroup(7L, 1L));
        assertCode(StatusCode.ATTACHMENT_GROUP_HAS_ITEMS, e);
        verify(attachmentGroupMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void deleteEmptyGroupLogicallyDeleted() {
        when(attachmentGroupMapper.selectById(1L)).thenReturn(group(1L, 7L));
        when(attachmentMapper.selectCount(any())).thenReturn(0L);

        attachmentService.deleteGroup(7L, 1L);
        verify(attachmentGroupMapper).deleteById(1L);
    }

    // ---------- 越权=不存在 ----------

    @Test
    void operatingOthersAttachmentTreatedAsNotExist() {
        when(attachmentMapper.selectById(1L)).thenReturn(attachment(1L, 99L));

        assertCode(StatusCode.ATTACHMENT_NOT_EXIST, assertThrows(BusinessException.class,
                () -> attachmentService.deleteMine(7L, 1L)));
        assertCode(StatusCode.ATTACHMENT_NOT_EXIST, assertThrows(BusinessException.class,
                () -> attachmentService.moveToGroup(7L, 1L, null)));
        verify(attachmentMapper, never()).deleteById(any(Long.class));
        // 越权拒绝时不得触碰存储对象
        verify(uploadService, never()).deleteObject(any());
    }

    @Test
    void operatingOthersGroupTreatedAsNotExist() {
        when(attachmentGroupMapper.selectById(1L)).thenReturn(group(1L, 99L));

        assertCode(StatusCode.ATTACHMENT_GROUP_NOT_EXIST, assertThrows(BusinessException.class,
                () -> attachmentService.renameGroup(7L, 1L, "改名")));
        assertCode(StatusCode.ATTACHMENT_GROUP_NOT_EXIST, assertThrows(BusinessException.class,
                () -> attachmentService.deleteGroup(7L, 1L)));
    }

    @Test
    void moveIntoOthersGroupRejected() {
        when(attachmentMapper.selectById(1L)).thenReturn(attachment(1L, 7L));
        when(attachmentGroupMapper.selectById(2L)).thenReturn(group(2L, 99L));

        assertCode(StatusCode.ATTACHMENT_GROUP_NOT_EXIST, assertThrows(BusinessException.class,
                () -> attachmentService.moveToGroup(7L, 1L, 2L)));
        verify(attachmentMapper, never()).update(any(), any());
    }

    @Test
    void moveOutOfGroupSkipsGroupCheck() {
        when(attachmentMapper.selectById(1L)).thenReturn(attachment(1L, 7L));

        attachmentService.moveToGroup(7L, 1L, null);

        // groupId=null 走 UpdateWrapper 显式 set（updateById 会跳过 null 字段）
        verify(attachmentMapper).update(any(), any());
        verify(attachmentGroupMapper, never()).selectById(any(Long.class));
    }
}
