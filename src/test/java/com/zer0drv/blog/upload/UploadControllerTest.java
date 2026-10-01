package com.zer0drv.blog.upload;

import com.zer0drv.blog.attachment.domain.Attachment;
import com.zer0drv.blog.attachment.service.AttachmentService;
import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.upload.controller.UploadController;
import com.zer0drv.blog.upload.service.UploadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UploadController 纯单测：上传成功后落附件库记录，响应 {url, id}。
 *
 * @author Yoruhaki
 */
@ExtendWith(MockitoExtension.class)
class UploadControllerTest {

    @Mock
    private UploadService uploadService;

    @Mock
    private AttachmentService attachmentService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @InjectMocks
    private UploadController uploadController;

    @Test
    void uploadImagePersistsAttachmentAndReturnsId() {
        MultipartFile file = mock(MultipartFile.class);
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn("7");
        // 每日配额（#6-4）：@Value 字段在纯 Mockito 单测中不注入，手动设置为默认值 100
        ReflectionTestUtils.setField(uploadController, "dailyLimit", 100L);
        // mock Redis 计数器，返回首次上传
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenReturn(1L);
        when(uploadService.uploadImage(file)).thenReturn("/uploads/202501/uuid.png");
        Attachment saved = new Attachment();
        saved.setId(42L);
        when(attachmentService.recordUpload(eq(7L), eq(file), eq("/uploads/202501/uuid.png")))
                .thenReturn(saved);

        Result<Map<String, String>> result = uploadController.uploadImage(jwt, file);

        assertEquals("200", result.getCode());
        assertEquals("/uploads/202501/uuid.png", result.getData().get("url"));
        assertEquals("42", result.getData().get("id"));
        // 落库用户取自 JWT sub
        verify(attachmentService).recordUpload(7L, file, "/uploads/202501/uuid.png");
    }
}
