package com.zer0drv.blog.it;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 附件库接口级集成测试：上传落库（本地磁盘 fallback，测试 yaml 已 blog.minio.enabled=false）、
 * 本人数据 CRUD + 分组 CRUD（组内有附件拒删 40071）、越权按不存在处理、管理端全量查与逻辑删。
 *
 * @author Yoruhaki
 */
class AttachmentIntegrationTests extends IntegrationTestSupport {

    /**
     * 直接预置一条附件记录并返回 id（绕过真实上传，供 CRUD 用例使用）
     */
    private long seedAttachment(long userId, Long groupId, String filename) {
        jdbcTemplate.update(
                "INSERT INTO attachment (user_id, group_id, url, object_key, storage, filename, media_type, size_bytes)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                userId, groupId, "/uploads/202501/" + filename, "202501/" + filename,
                "LOCAL", filename, "image/png", 1024L);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM attachment WHERE user_id = ? AND filename = ?", Long.class, userId, filename);
    }

    @Test
    void uploadImagePersistsAttachmentRecord() throws Exception {
        String username = unique("it_att_up_");
        long userId = seedUser(username, "上传者", "AUTHOR");
        String bearer = bearerOf(username);

        MockMultipartFile file = new MockMultipartFile(
                "file", "it-封面.png", "image/png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});
        MvcResult result = mockMvc.perform(multipart("/upload/image")
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.url").exists())
                .andExpect(jsonPath("$.data.id").exists())
                .andReturn();

        String url = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("url").asString();
        long attachmentId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        assertTrue(url.startsWith("/uploads/"), "本地 fallback 应返回 /uploads/** 相对 URL");

        // 落库字段核对
        var row = jdbcTemplate.queryForMap("SELECT * FROM attachment WHERE id = ?", attachmentId);
        assertEquals(userId, ((Number) row.get("user_id")).longValue());
        assertEquals(url, row.get("url"));
        assertEquals("LOCAL", row.get("storage"));
        assertEquals("it-封面.png", row.get("filename"));
        assertEquals("image/png", row.get("media_type"));
        assertTrue(row.get("object_key").toString().endsWith(".png"));

        // 本人附件列表可见
        mockMvc.perform(get("/attachments")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(attachmentId))
                .andExpect(jsonPath("$.data.records[0].url").value(url));
    }

    @Test
    void anonymousUploadRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "x.png", "image/png", new byte[]{0x01});
        mockMvc.perform(multipart("/upload/image").file(file))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void groupCrudAndMoveFlow() throws Exception {
        String username = unique("it_att_grp_");
        long userId = seedUser(username, "分组用户", "USER");
        String bearer = bearerOf(username);
        long attachmentId = seedAttachment(userId, null, unique("分组流程.png"));

        // 新建分组
        MvcResult created = mockMvc.perform(post("/attachments/groups")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"壁纸\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andReturn();
        long groupId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").asLong();

        // 重命名
        mockMvc.perform(put("/attachments/groups/{id}", groupId)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"壁纸精选\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        // 移入分组后组内计数 = 1
        mockMvc.perform(put("/attachments/{id}", attachmentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":" + groupId + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/attachments/groups")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(groupId))
                .andExpect(jsonPath("$.data[0].name").value("壁纸精选"))
                .andExpect(jsonPath("$.data[0].count").value(1));

        // 组内有附件拒删 → 40071
        mockMvc.perform(delete("/attachments/groups/{id}", groupId)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40071"));

        // 按分组过滤列表
        mockMvc.perform(get("/attachments")
                        .param("groupId", String.valueOf(groupId))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        // 移出分组（groupId=null）→ 空组可删
        mockMvc.perform(put("/attachments/{id}", attachmentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":null}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/attachments/groups/{id}", groupId)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
        mockMvc.perform(get("/attachments/groups")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void crossUserAccessTreatedAsNotExist() throws Exception {
        String owner = unique("it_att_owner_");
        long ownerId = seedUser(owner, "属主", "USER");
        String other = unique("it_att_other_");
        seedUser(other, "路人", "USER");
        String otherBearer = bearerOf(other);

        long attachmentId = seedAttachment(ownerId, null, unique("越权附件.png"));
        jdbcTemplate.update("INSERT INTO attachment_group (user_id, name, sort) VALUES (?,?,0)",
                ownerId, unique("越权分组"));
        long groupId = jdbcTemplate.queryForObject(
                "SELECT id FROM attachment_group WHERE user_id = ?", Long.class, ownerId);

        // 他人移动/删除我的附件 → 40069（不暴露存在性）
        mockMvc.perform(put("/attachments/{id}", attachmentId)
                        .header(HttpHeaders.AUTHORIZATION, otherBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40069"));
        mockMvc.perform(delete("/attachments/{id}", attachmentId)
                        .header(HttpHeaders.AUTHORIZATION, otherBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40069"));

        // 他人删除/改名我的分组 → 40070
        mockMvc.perform(delete("/attachments/groups/{id}", groupId)
                        .header(HttpHeaders.AUTHORIZATION, otherBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40070"));

        // 他人列表看不到我的附件
        mockMvc.perform(get("/attachments")
                        .header(HttpHeaders.AUTHORIZATION, otherBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void adminPageAllAndLogicDelete() throws Exception {
        String owner = unique("it_att_adm_");
        long ownerId = seedUser(owner, "属主", "USER");
        long attachmentId = seedAttachment(ownerId, null, unique("管理附件.png"));

        String admin = unique("it_att_admin_");
        seedUser(admin, "管理员", "ADMIN");
        String adminBearer = bearerOf(admin);

        // 全量查询（按上传者过滤），VO 含 userId
        mockMvc.perform(get("/admin/attachments")
                        .param("userId", String.valueOf(ownerId))
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(attachmentId))
                .andExpect(jsonPath("$.data.records[0].userId").value(ownerId));

        // 管理端逻辑删（不删存储对象）→ 属主列表清空
        mockMvc.perform(delete("/admin/attachments/{id}", attachmentId)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
        mockMvc.perform(get("/attachments")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        // 删除不存在的附件 → 40069
        mockMvc.perform(delete("/admin/attachments/{id}", attachmentId)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40069"));
    }
}
