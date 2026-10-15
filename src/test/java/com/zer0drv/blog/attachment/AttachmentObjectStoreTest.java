package com.zer0drv.blog.attachment;

import com.zer0drv.blog.attachment.service.*;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.*;
import java.time.ZonedDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AttachmentObjectStoreTest {
    @TempDir Path root;
    private static final String KEY="202601/1234567890abcdef1234567890abcdef.png";

    @SuppressWarnings("unchecked")
    private AttachmentObjectStore store(MinioClient client) {
        ObjectProvider<MinioClient> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        var store=new AttachmentObjectStore(provider);
        ReflectionTestUtils.setField(store,"uploadDir",root.toString());
        ReflectionTestUtils.setField(store,"minioEnabled",true);
        ReflectionTestUtils.setField(store,"bucket","images");
        ReflectionTestUtils.setField(store,"endpoint","http://storage.example");
        return store;
    }

    @Test
    void minioInventoryStatDeleteAndNamespaceChanges() throws Exception {
        MinioClient client=mock(MinioClient.class);
        Item item=mock(Item.class);
        when(item.objectName()).thenReturn(KEY);when(item.size()).thenReturn(12L);
        when(item.etag()).thenReturn("etag");when(item.lastModified()).thenReturn(ZonedDateTime.now().minusDays(2));
        when(client.listObjects(any(ListObjectsArgs.class))).thenReturn(List.of(new Result<>(item)));
        var response=mock(StatObjectResponse.class);
        var modified=item.lastModified();
        when(response.etag()).thenReturn("etag");when(response.size()).thenReturn(12L);when(response.lastModified()).thenReturn(modified);
        when(client.statObject(any(StatObjectArgs.class))).thenReturn(response);
        var store=store(client);var object=store.inventory().getFirst();
        assertEquals("MINIO",object.storage());assertEquals(store.stat("MINIO",KEY).fingerprint(),object.fingerprint());
        assertTrue(store.delete(object));
        var args=org.mockito.ArgumentCaptor.forClass(RemoveObjectArgs.class);verify(client).removeObject(args.capture());
        assertEquals("images",args.getValue().bucket());assertEquals(KEY,args.getValue().object());
        ReflectionTestUtils.setField(store,"bucket","new-images");
        assertNotEquals(object.fingerprint(),store.stat("MINIO",KEY).fingerprint());
        assertThrows(IllegalStateException.class,()->store.delete(object));
    }

    @Test
    void missingKeyIsDistinctFromPermissionAndStorageFailures() throws Exception {
        MinioClient client=mock(MinioClient.class);var store=store(client);
        ErrorResponseException missing=mock(ErrorResponseException.class,RETURNS_DEEP_STUBS);
        when(missing.errorResponse().code()).thenReturn("NoSuchKey");
        when(client.statObject(any(StatObjectArgs.class))).thenThrow(missing);
        assertNull(store.stat("MINIO",KEY));
        ErrorResponseException forbidden=mock(ErrorResponseException.class,RETURNS_DEEP_STUBS);
        when(forbidden.errorResponse().code()).thenReturn("AccessDenied");
        doThrow(forbidden).when(client).statObject(any(StatObjectArgs.class));
        assertThrows(ErrorResponseException.class,()->store.stat("MINIO",KEY));
        when(client.listObjects(any(ListObjectsArgs.class))).thenThrow(new IllegalStateException("offline"));
        assertThrows(IllegalStateException.class,store::inventory);
    }

    @Test
    void onlyManagedLocalFilesAreDeletedAndReplacementIsRejected() throws Exception {
        var store=store(null);ReflectionTestUtils.setField(store,"minioEnabled",false);
        Files.createDirectories(root.resolve("202601"));Files.write(root.resolve(KEY),new byte[]{1,2});
        Files.writeString(root.resolve("keep.txt"),"foreign file");
        var object=store.inventory().getFirst();assertEquals(1,store.inventory().size());
        Files.write(root.resolve(KEY),new byte[]{1,2,3});
        assertThrows(IllegalStateException.class,()->store.delete(object));
        assertTrue(store.delete(store.stat("LOCAL",KEY)));assertNull(store.stat("LOCAL",KEY));
        assertTrue(Files.exists(root.resolve("keep.txt")));
        assertThrows(IllegalArgumentException.class,()->store.stat("LOCAL","../escape.png"));
    }

    @Test
    void localSymlinkCannotEscapeRoot() throws Exception {
        var store=store(null);ReflectionTestUtils.setField(store,"minioEnabled",false);
        Path external=Files.createTempDirectory("attachment-outside-");
        try {
            try { Files.createSymbolicLink(root.resolve("202601"),external); }
            catch (java.io.IOException|UnsupportedOperationException unavailable) { org.junit.jupiter.api.Assumptions.abort("OS does not allow test symlinks"); }
            assertThrows(IllegalStateException.class,()->store.stat("LOCAL",KEY));
            assertTrue(store.inventory().isEmpty());
        } finally { Files.deleteIfExists(root.resolve("202601"));Files.delete(external); }
    }

    @Test
    void referenceParserProtectsEscapedAndOldHostUrlsConservatively() {
        String file=KEY.substring(7);
        assertTrue(AttachmentObjectKeys.managed(KEY));
        assertFalse(AttachmentObjectKeys.managed("202613/"+file));
        assertFalse(AttachmentObjectKeys.managed("202601/../../"+file));
        assertTrue(AttachmentObjectKeys.references("https://old.example/uploads/"+KEY).contains(file));
        assertTrue(AttachmentObjectKeys.references(file.replace("a","%61")).contains(file));
        assertTrue(AttachmentObjectKeys.references(file.replace("a","%61")+"?invalid=%zz").contains(file));
        assertTrue(AttachmentObjectKeys.references(file.replace("a","&#97;")).contains(file));
    }
}
