package com.zer0drv.blog.upload;

import com.zer0drv.blog.upload.service.impl.UploadServiceImpl;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UploadServiceImpl.deleteObject 纯单测：本地磁盘物理删除（含路径穿越防护、文件缺失容忍）、
 * MinIO removeObject（含失败容忍=不抛异常）、客户端缺失时回落本地。
 *
 * @author Yoruhaki
 */
@ExtendWith(MockitoExtension.class)
class UploadServiceImplTest {

    @TempDir
    Path tempDir;

    @Mock
    private MinioClient minioClient;

    /**
     * 组装被测服务：@Value 字段用 ReflectionTestUtils 注入（uploadDir 指向临时目录）
     */
    private UploadServiceImpl service(ObjectProvider<MinioClient> provider, boolean minioEnabled) {
        UploadServiceImpl service = new UploadServiceImpl(provider);
        ReflectionTestUtils.setField(service, "uploadDir", tempDir.toString());
        ReflectionTestUtils.setField(service, "minioEnabled", minioEnabled);
        ReflectionTestUtils.setField(service, "minioBucket", "blog-images");
        return service;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MinioClient> providerReturning(MinioClient client) {
        ObjectProvider<MinioClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
    }

    // ---------- 本地磁盘 ----------

    @SuppressWarnings("unchecked")
    @Test
    void deleteObjectRemovesLocalFile() throws Exception {
        Path stored = tempDir.resolve("202501").resolve("uuid.png");
        Files.createDirectories(stored.getParent());
        Files.write(stored, new byte[]{0x01});
        // minioEnabled=false 时不触碰 provider（短路），本地 fallback 与上传一致
        UploadServiceImpl service = service(mock(ObjectProvider.class), false);

        service.deleteObject("202501/uuid.png");

        assertFalse(Files.exists(stored), "删除后本地文件应不存在");
    }

    @SuppressWarnings("unchecked")
    @Test
    void deleteObjectToleratesMissingLocalFile() {
        UploadServiceImpl service = service(mock(ObjectProvider.class), false);

        // 文件不存在：只告警，不抛异常
        assertDoesNotThrow(() -> service.deleteObject("202501/ghost.png"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void deleteObjectRejectsPathTraversal() throws Exception {
        // uploadDir 指向 tempDir/uploads，越界目标放到其上一级
        Path uploadRoot = tempDir.resolve("uploads");
        Files.createDirectories(uploadRoot);
        Path outside = tempDir.resolve("evil.png");
        Files.write(outside, new byte[]{0x01});
        UploadServiceImpl service = service(mock(ObjectProvider.class), false);
        ReflectionTestUtils.setField(service, "uploadDir", uploadRoot.toString());

        assertDoesNotThrow(() -> service.deleteObject("../evil.png"));
        assertDoesNotThrow(() -> service.deleteObject("202501/.."));
        assertDoesNotThrow(() -> service.deleteObject("no-slash.png"));

        assertTrue(Files.exists(outside), "上传根目录之外的文件不得被删除");
        assertTrue(Files.exists(uploadRoot), "上传根目录本身不得被删除");
    }

    @Test
    void deleteObjectSkipsBlankKey() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MinioClient> provider = mock(ObjectProvider.class);
        UploadServiceImpl service = service(provider, false);

        assertDoesNotThrow(() -> service.deleteObject(""));
        assertDoesNotThrow(() -> service.deleteObject(null));

        // 空对象名直接跳过，连客户端解析都不应发生
        verify(provider, never()).getIfAvailable();
    }

    // ---------- MinIO ----------

    @Test
    void deleteObjectRemovesMinioObject() throws Exception {
        UploadServiceImpl service = service(providerReturning(minioClient), true);

        service.deleteObject("202501/uuid.png");

        ArgumentCaptor<RemoveObjectArgs> captor = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(minioClient).removeObject(captor.capture());
        assertEquals("blog-images", captor.getValue().bucket());
        assertEquals("202501/uuid.png", captor.getValue().object());
    }

    @Test
    void deleteObjectToleratesMinioFailure() throws Exception {
        doThrow(new RuntimeException("MinIO down")).when(minioClient).removeObject(any());
        UploadServiceImpl service = service(providerReturning(minioClient), true);

        // MinIO 不可用：只告警，不抛异常（不回滚已完成的记录删除）
        assertDoesNotThrow(() -> service.deleteObject("202501/uuid.png"));
    }

    @Test
    void deleteObjectFallsBackToLocalWhenClientMissing() throws Exception {
        // minioEnabled=true 但 Bean 缺失：与上传一致回落本地磁盘
        Path stored = tempDir.resolve("202502").resolve("uuid2.png");
        Files.createDirectories(stored.getParent());
        Files.write(stored, new byte[]{0x02});
        UploadServiceImpl service = service(providerReturning(null), true);

        service.deleteObject("202502/uuid2.png");

        assertFalse(Files.exists(stored), "MinIO 客户端缺失时应按本地文件删除");
    }
}
