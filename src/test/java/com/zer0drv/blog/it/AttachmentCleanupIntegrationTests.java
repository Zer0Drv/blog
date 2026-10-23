package com.zer0drv.blog.it;

import com.zer0drv.blog.attachment.service.*;
import com.zer0drv.blog.common.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AttachmentCleanupIntegrationTests extends IntegrationTestSupport {
    @Autowired private AttachmentCleanupService cleanup;
    @Autowired private AttachmentObjectStore store;
    @Autowired private AttachmentReferenceSnapshot snapshot;
    @Autowired private AttachmentReferenceGuard guard;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private StringRedisTemplate redis;
    private Path root;
    private Object previousRoot;
    private final List<Path> files = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        root = Files.createTempDirectory("attachment-cleanup-it-");
        previousRoot = ReflectionTestUtils.getField(store, "uploadDir");
        ReflectionTestUtils.setField(store, "uploadDir", root.toString());
        when(redis.scan(any())).thenReturn(mock(Cursor.class));
        jdbcTemplate.update("DELETE FROM attachment_cleanup");
    }

    @AfterEach
    void teardown() throws Exception {
        ReflectionTestUtils.setField(store, "uploadDir", previousRoot);
        for (Path path : files) Files.deleteIfExists(path);
        Files.deleteIfExists(root.resolve("202601"));
        Files.deleteIfExists(root);
        jdbcTemplate.update("DELETE FROM attachment_cleanup");
        reset(redis);
    }

    private String object(boolean old) throws Exception {
        String key = "202601/" + UUID.randomUUID().toString().replace("-", "") + ".png";
        Path path = root.resolve(key);
        Files.createDirectories(path.getParent()); Files.write(path, new byte[]{1,2,3,4}); files.add(path);
        if (old) Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        return key;
    }
    private long id(String key) { return jdbcTemplate.queryForObject("SELECT id FROM attachment_cleanup WHERE object_key=?", Long.class, key); }
    private String state(String key) { return jdbcTemplate.queryForObject("SELECT state FROM attachment_cleanup WHERE object_key=?", String.class, key); }
    private void age(String key) { jdbcTemplate.update("UPDATE attachment_cleanup SET eligible_at=? WHERE object_key=?", Timestamp.from(Instant.now().minusSeconds(1)), key); }
    private String admin() throws Exception { String name = unique("cleanup_admin_"); seedUser(name,"管理员","ADMIN"); return bearerOf(name); }

    @Test
    void previewRequiresAdminAndExistingRecordWithoutChangingRetention() throws Exception {
        String key = object(true), bearer = admin();
        cleanup.scan(1);
        long record = id(key);
        mockMvc.perform(get("/admin/attachments/cleanup/{id}/preview", record)).andExpect(status().isUnauthorized());
        String user = unique("preview_user_"); seedUser(user, "用户", "USER");
        mockMvc.perform(get("/admin/attachments/cleanup/{id}/preview", record).header(HttpHeaders.AUTHORIZATION, bearerOf(user)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/attachments/cleanup/{id}/preview", record).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().bytes(new byte[]{1,2,3,4}));
        assertEquals("OBSERVING", state(key));
        mockMvc.perform(get("/admin/attachments/cleanup/{id}/preview?download=true", record).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")));
        Files.delete(root.resolve(key));
        mockMvc.perform(get("/admin/attachments/cleanup/{id}/preview", record).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.code").value("40023"));
        jdbcTemplate.update("UPDATE attachment_cleanup SET state='DELETED' WHERE id=?", record);
        assertThrows(BusinessException.class, () -> cleanup.preview(record));
    }

    @ParameterizedTest
    @ValueSource(strings = {"html", "svg", "pdf", "zip"})
    void unsupportedObjectsNeverBecomePreviewOrDownloadTargets(String extension) throws Exception {
        String key = object(true);
        cleanup.scan(1);
        long record = id(key);
        String unsupported = key.substring(0, key.length() - 3) + extension;
        Path path = root.resolve(unsupported);
        Files.writeString(path, "<script>alert('untrusted')</script>"); files.add(path);
        jdbcTemplate.update("UPDATE attachment_cleanup SET object_key=? WHERE id=?", unsupported, record);
        String bearer = admin();
        for (String suffix : List.of("", "?download=true"))
            mockMvc.perform(get("/admin/attachments/cleanup/" + record + "/preview" + suffix)
                    .header(HttpHeaders.AUTHORIZATION, bearer))
                    .andExpect(jsonPath("$.code").value("40023"));
        assertTrue(Files.exists(path));
        assertEquals("OBSERVING", state(unsupported));
    }

    @Test
    void adminFlowMarksWaitsRestoresAndKeepsFiles() throws Exception {
        String key = object(true), fresh = object(false), bearer = admin();
        jdbcTemplate.update("INSERT INTO attachment(user_id,url,object_key,storage,filename,deleted) VALUES(1,?,?, 'LOCAL','image.png',1)", "/uploads/"+key,key);
        mockMvc.perform(post("/admin/attachments/cleanup/scan").header(HttpHeaders.AUTHORIZATION,bearer))
                .andExpect(jsonPath("$.code").value("200")).andExpect(jsonPath("$.data.marked").value(1));
        assertEquals("OBSERVING",state(key));
        assertEquals(0,jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attachment_cleanup WHERE object_key=?",Integer.class,fresh));
        mockMvc.perform(delete("/admin/attachments/cleanup/{id}",id(key)).header(HttpHeaders.AUTHORIZATION,bearer))
                .andExpect(jsonPath("$.code").value("40023"));
        assertTrue(Files.exists(root.resolve(key)));
        mockMvc.perform(get("/admin/attachments/cleanup").header(HttpHeaders.AUTHORIZATION,bearer))
                .andExpect(jsonPath("$.data.records[0].canPurge").value(false));
        mockMvc.perform(post("/admin/attachments/cleanup/{id}/restore",id(key)).header(HttpHeaders.AUTHORIZATION,bearer))
                .andExpect(jsonPath("$.code").value("200"));
        assertEquals("RESTORED",state(key));
        assertEquals(0,jdbcTemplate.queryForObject("SELECT deleted FROM attachment WHERE object_key=?",Integer.class,key));
        cleanup.scan(1); assertEquals("RESTORED",state(key));
        // Removing it again releases retention but starts an entirely new seven-day observation.
        cleanup.releaseRestored("LOCAL",key);
        jdbcTemplate.update("UPDATE attachment SET deleted=1 WHERE object_key=?",key);
        cleanup.scan(1); assertEquals("OBSERVING",state(key));
        assertThrows(BusinessException.class,()->cleanup.purge(id(key),1));
    }

    @Test
    void maturePurgeIsIdempotentAndReportsOnlyConfirmedBytes() throws Exception {
        String key=object(true), bearer=admin(); cleanup.scan(1); age(key);
        mockMvc.perform(delete("/admin/attachments/cleanup/{id}",id(key)).header(HttpHeaders.AUTHORIZATION,bearer))
                .andExpect(jsonPath("$.data.state").value("DELETED"));
        assertFalse(Files.exists(root.resolve(key)));
        assertEquals("DELETED",cleanup.purge(id(key),1));
        mockMvc.perform(get("/admin/attachments/cleanup/report").header(HttpHeaders.AUTHORIZATION,bearer))
                .andExpect(jsonPath("$.data.reclaimedBytes").value(4));
        assertThrows(BusinessException.class,()->cleanup.restore(id(key),1));
    }

    @ParameterizedTest
    @ValueSource(strings={"library","article","trash","version","comment","page","avatar","site","friend","message"})
    void retainedSourcesProtectObjects(String source) throws Exception {
        String key=object(true), url="/uploads/"+key;
        long user=seedUser(unique("cleanup_owner_"),"作者","AUTHOR");
        long article=seedArticle(user,unique("cleanup_article_"),"DRAFT");
        switch(source) {
            case "library" -> jdbcTemplate.update("INSERT INTO attachment(user_id,url,object_key,filename) VALUES(?,?,?,'image.png')",user,url,key);
            case "article","trash" -> jdbcTemplate.update("UPDATE article SET content=?,deleted=? WHERE id=?",url,"trash".equals(source)?1:0,article);
            case "version" -> jdbcTemplate.update("INSERT INTO article_version(article_id,version,title,content,editor_type) VALUES(?,1,'snapshot',?,'MARKDOWN')",article,url);
            case "comment" -> jdbcTemplate.update("INSERT INTO comment(article_id,user_id,content,deleted) VALUES(?,?,?,1)",article,user,url);
            case "page" -> jdbcTemplate.update("INSERT INTO custom_page(slug,title,content,deleted) VALUES(?,'page',?,1)",unique("cleanup-page-"),url);
            case "avatar" -> jdbcTemplate.update("UPDATE `user` SET avatar=? WHERE id=?",url,user);
            case "site" -> jdbcTemplate.update("INSERT INTO site_config(config_key,config_value) VALUES(?,?)",unique("cleanup.logo."),url);
            case "friend" -> jdbcTemplate.update("INSERT INTO friend_link(name,url,logo) VALUES('friend','https://example.com',?)",url);
            case "message" -> jdbcTemplate.update("INSERT INTO private_message(sender_id,receiver_id,content) VALUES(?,?,?)",user,user,url);
        }
        cleanup.scan(1);
        assertEquals(0,jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attachment_cleanup WHERE object_key=?",Integer.class,key));
        assertTrue(Files.exists(root.resolve(key)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisDraftProtectsEncodedImageAndFailureLeavesNoMarks() throws Exception {
        String key=object(true), other=object(true);
        Cursor<String> cursor=mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true,false); when(cursor.next()).thenReturn("blog:autosave:1:1");
        when(redis.scan(any())).thenReturn(cursor);
        when(redis.opsForValue().get("blog:autosave:1:1")).thenReturn("{\"content\":\"/uploads/"+key.replace("/","\\/")+"\"}");
        cleanup.scan(1);
        assertEquals(0,jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attachment_cleanup WHERE object_key=?",Integer.class,key));
        assertEquals("OBSERVING",state(other));
        jdbcTemplate.update("DELETE FROM attachment_cleanup");
        when(redis.scan(any())).thenThrow(new IllegalStateException("Redis unavailable"));
        assertThrows(BusinessException.class,()->cleanup.scan(1));
        assertEquals(0,jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attachment_cleanup",Integer.class));
    }

    @Test
    void newReferenceCancelsDeletionAndRescanningRestartsObservation() throws Exception {
        String key=object(true); cleanup.scan(1); age(key);
        long user=seedUser(unique("cleanup_ref_"),"作者","AUTHOR");
        long article=seedArticle(user,unique("cleanup_ref_article_"),"DRAFT");
        jdbcTemplate.update("UPDATE article SET cover=? WHERE id=?","/uploads/"+key,article);
        assertEquals("RETAINED",cleanup.purge(id(key),1)); assertTrue(Files.exists(root.resolve(key)));
        jdbcTemplate.update("UPDATE article SET cover='' WHERE id=?",article);
        cleanup.scan(1); assertEquals("OBSERVING",state(key));
        assertThrows(BusinessException.class,()->cleanup.purge(id(key),1));
    }

    @Test
    void changedObjectRestartsObservationAndMissingObjectsDoNotInflateReclamation() throws Exception {
        String key=object(true); cleanup.scan(1); age(key);
        Files.write(root.resolve(key),new byte[]{9,8,7,6,5});
        assertEquals("OBSERVING",cleanup.purge(id(key),1)); assertTrue(Files.exists(root.resolve(key)));
        age(key); Files.delete(root.resolve(key));
        assertEquals("MISSING",cleanup.purge(id(key),1)); assertEquals(0L,cleanup.report().get("reclaimedBytes"));
    }

    @Test
    void storageFailureLeavesDurableRetryAndBlocksNewReferences() throws Exception {
        String key=object(true); cleanup.scan(1); age(key);
        var broken=spy(store); doThrow(new IllegalStateException("Storage unavailable")).doCallRealMethod().when(broken).delete(any());
        var service=new AttachmentCleanupService(jdbcTemplate,guard,snapshot,broken,transactions);
        assertThrows(BusinessException.class,()->service.purge(id(key),1)); assertEquals("PURGING",state(key));
        assertTrue(Files.exists(root.resolve(key)));
        var tx=new TransactionTemplate(transactions);
        assertThrows(BusinessException.class,()->tx.executeWithoutResult(s->guard.protect("/uploads/"+key)));
        assertEquals("DELETED",service.purge(id(key),1)); assertEquals(4L,cleanup.report().get("reclaimedBytes"));
        assertThrows(BusinessException.class,()->tx.executeWithoutResult(s->guard.protect("/uploads/"+key)));
    }

    @Test
    void concurrentContentCommitIsSeenBeforePurge() throws Exception {
        String key=object(true); cleanup.scan(1); age(key);
        long user=seedUser(unique("cleanup_race_"),"作者","AUTHOR");
        long article=seedArticle(user,unique("cleanup_race_article_"),"DRAFT");
        CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Future<?> write=executor.submit(()->new TransactionTemplate(transactions).executeWithoutResult(s->{
                guard.protect("/uploads/"+key); locked.countDown();
                try { assertTrue(release.await(5,TimeUnit.SECONDS)); } catch(InterruptedException e) { throw new RuntimeException(e); }
                jdbcTemplate.update("UPDATE article SET content=? WHERE id=?","/uploads/"+key,article);
            }));
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            Future<String> purge=executor.submit(()->cleanup.purge(id(key),1));
            try { assertThrows(TimeoutException.class,()->purge.get(150,TimeUnit.MILLISECONDS)); }
            finally { release.countDown(); }
            write.get(5,TimeUnit.SECONDS); assertEquals("RETAINED",purge.get(5,TimeUnit.SECONDS));
        }
        assertTrue(Files.exists(root.resolve(key)));
    }

    @Test
    void anonymousAndNonAdminCannotScanRestoreDeleteOrReadReports() throws Exception {
        mockMvc.perform(get("/admin/attachments/cleanup")).andExpect(status().isUnauthorized());
        String name=unique("cleanup_reader_"); seedUser(name,"普通用户","AUTHOR"); String bearer=bearerOf(name);
        mockMvc.perform(get("/admin/attachments/cleanup/report").header(HttpHeaders.AUTHORIZATION,bearer)).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/attachments/cleanup/scan").header(HttpHeaders.AUTHORIZATION,bearer)).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/attachments/cleanup/1/restore").header(HttpHeaders.AUTHORIZATION,bearer)).andExpect(status().isForbidden());
        mockMvc.perform(delete("/admin/attachments/cleanup/1").header(HttpHeaders.AUTHORIZATION,bearer)).andExpect(status().isForbidden());
    }

    @Test
    void savedContentCannotReintroducePurgedImage() throws Exception {
        String key=object(true); cleanup.scan(1); age(key); cleanup.purge(id(key),1);
        String name=unique("cleanup_writer_"); seedUser(name,"作者","AUTHOR"); String bearer=bearerOf(name);
        mockMvc.perform(post("/articles").header(HttpHeaders.AUTHORIZATION,bearer).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title","new article","content","![](/uploads/"+key+")","editorType","MARKDOWN","status","DRAFT"))))
                .andExpect(jsonPath("$.code").value("40023"));
    }
}
