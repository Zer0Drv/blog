package com.zer0drv.blog.it;

import com.zer0drv.blog.auth.service.AuthCookieService;
import com.zer0drv.blog.auth.service.TokenService;
import com.zer0drv.blog.common.captcha.CaptchaProperties;
import com.zer0drv.blog.sysconfig.service.SysConfigService;
import com.zer0drv.blog.upload.service.UploadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SysConfigIntegrationTests extends IntegrationTestSupport {
    @Autowired private SysConfigService configs;
    @Autowired private CaptchaProperties captcha;
    @Autowired private AuthCookieService cookies;
    @Autowired private TokenService tokens;
    @Autowired private JwtDecoder decoder;
    @Autowired private UploadService uploads;
    @Autowired private Environment environment;
    @Autowired private PlatformTransactionManager transactions;

    @AfterEach
    void clearOverrides() {
        jdbcTemplate.update("UPDATE sys_config SET config_value=NULL");
        configs.refresh();
    }

    private String admin() throws Exception {
        String username = unique("sys_admin_");
        seedUser(username, "配置管理员", "ADMIN");
        return bearerOf(username);
    }

    private SysConfigService instance(Environment env) {
        var service = new SysConfigService(jdbcTemplate, env, objectMapper, transactions);
        ReflectionTestUtils.invokeMethod(service, "initialize");
        return service;
    }

    @Test
    void adminListIsGroupedTypedAndDoesNotExposeSecretsOrPublicConfig() throws Exception {
        String bearer = admin();
        var result = mockMvc.perform(get("/admin/sys/config").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.length()").value(20)).andReturn();
        String json = result.getResponse().getContentAsString();
        assertFalse(json.contains("jwt.secret"));
        assertFalse(json.contains("MINIO_SECRET_KEY"));
        var row = configs.list().stream().filter(r -> r.get("key").equals("blog.upload.allowed-extensions")).findFirst().orElseThrow();
        assertEquals("JSON", row.get("type"));
        assertTrue(row.get("value") instanceof List);
        mockMvc.perform(get("/site/config")).andExpect(jsonPath("$.data['jwt.expiration-ms']").doesNotExist());
        assertEquals(20, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_config", Integer.class));
    }

    @Test
    void rejectsAnonymousAndNonAdminReadsWritesResetsAndAudit() throws Exception {
        mockMvc.perform(get("/admin/sys/config")).andExpect(status().isUnauthorized());
        String username = unique("sys_user_");
        seedUser(username, "普通用户", "AUTHOR");
        String bearer = bearerOf(username);
        mockMvc.perform(get("/admin/sys/config").header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isForbidden());
        mockMvc.perform(put("/admin/sys/config").header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"blog.upload-daily-limit\":8}")).andExpect(status().isForbidden());
        mockMvc.perform(delete("/admin/sys/config/blog.upload-daily-limit").header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/sys/config/audit").header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isForbidden());
    }

    @Test
    void hotSettingsPersistRefreshConsumersAndAuditOldAndNewValuesWithActor() throws Exception {
        String bearer = admin();
        long before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_config_audit", Long.class);
        mockMvc.perform(put("/admin/sys/config").header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blog.captcha.scenes.login.threshold\":2,\"blog.captcha.scenes.login.window-seconds\":120,\"blog.captcha.code-ttl-seconds\":90,\"blog.upload.max-bytes\":1024,\"blog.upload.allowed-extensions\":[\"png\"],\"blog.search.fulltext-enabled\":false}"))
                .andExpect(jsonPath("$.code").value("200"));
        assertEquals(2, captcha.sceneOf("login").getThreshold());
        assertEquals(120, captcha.sceneOf("login").getWindowSeconds());
        assertEquals(90, captcha.getCodeTtlSeconds());
        assertEquals(List.of("png"), configs.getList("blog.upload.allowed-extensions"));
        assertEquals(before + 6, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_config_audit", Long.class));
        var tooLarge = assertThrows(com.zer0drv.blog.common.exception.BusinessException.class, () -> uploads.uploadImage(
                new MockMultipartFile("file", "cover.png", "image/png", new byte[1025])));
        assertTrue(tooLarge.getMessage().contains("1024"));
        assertThrows(com.zer0drv.blog.common.exception.BusinessException.class, () -> uploads.uploadImage(
                new MockMultipartFile("file", "cover.jpg", "image/jpeg", new byte[]{1})));
        mockMvc.perform(get("/admin/sys/config/audit").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.records[0].action").value("SET"))
                .andExpect(jsonPath("$.data.records[0].operatorId").isNotEmpty())
                .andExpect(jsonPath("$.data.records[0].newSource").value("DATABASE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"jwt.secret\":\"must-not-store\"}", "{\"blog.upload-daily-limit\":0}",
            "{\"blog.upload-daily-limit\":1.5}", "{\"blog.upload-daily-limit\":\"10\"}",
            "{\"blog.search.fulltext-enabled\":\"false\"}", "{\"blog.upload.allowed-extensions\":[]}",
            "{\"blog.upload.allowed-extensions\":[\"svg\"]}", "{\"blog.upload.allowed-extensions\":[\"png\",\"png\"]}",
            "{\"blog.auth.cookie.same-site\":\"unsafe\"}", "{\"blog.upload-daily-limit\":null}",
            "{\"blog.upload-daily-limit\":5,\"jwt.expiration-ms\":1}"
    })
    void invalidTypeRangeUnknownKeyAndMixedBatchWriteNothing(String body) throws Exception {
        String bearer = admin();
        long before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_config_audit", Long.class);
        mockMvc.perform(put("/admin/sys/config").header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value("40023"));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_config WHERE config_value IS NOT NULL", Integer.class));
        assertEquals(before, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_config_audit", Long.class));
    }

    @Test
    void resetRestoresDeploymentFallbackAndAuditPreservesOriginalSource() throws Exception {
        String bearer = admin();
        long fallback = configs.getLong("blog.upload-daily-limit");
        configs.update(Map.of("blog.upload-daily-limit", 7), 1L);
        mockMvc.perform(delete("/admin/sys/config/blog.upload-daily-limit").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.code").value("200"));
        assertEquals(fallback, configs.getLong("blog.upload-daily-limit"));
        assertNull(jdbcTemplate.queryForObject("SELECT config_value FROM sys_config WHERE config_key='blog.upload-daily-limit'", String.class));
        mockMvc.perform(get("/admin/sys/config/audit").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.data.records[0].action").value("RESET"))
                .andExpect(jsonPath("$.data.records[0].oldValue").value(7))
                .andExpect(jsonPath("$.data.records[0].newValue").value(fallback));
    }

    @Test
    void precedenceDatabaseThenEnvironmentThenYamlThenDefaultAndOtherInstanceRefresh() {
        var env = new MockEnvironment().withProperty("UPLOAD_DAILY_LIMIT", "17").withProperty("blog.upload-daily-limit", "23");
        var first = instance(env);
        var second = instance(env);
        assertEquals(17, first.getLong("blog.upload-daily-limit"));
        first.update(Map.of("blog.upload-daily-limit", 9), 1L);
        assertEquals(9, first.getLong("blog.upload-daily-limit"));
        assertEquals(17, second.getLong("blog.upload-daily-limit"));
        second.refreshOtherInstances();
        assertEquals(9, second.getLong("blog.upload-daily-limit"));
        second.reset("blog.upload-daily-limit", 1L);
        assertEquals(17, second.getLong("blog.upload-daily-limit"));
        assertEquals(23, instance(new MockEnvironment().withProperty("blog.upload-daily-limit", "23")).getLong("blog.upload-daily-limit"));
        assertEquals(100, instance(new MockEnvironment()).getLong("blog.upload-daily-limit"));
    }

    @Test
    void restartOnlyValuesStayAtBootValueUntilAProcessIsReinitialized() {
        long active = configs.getLong("jwt.expiration-ms");
        configs.update(Map.of("jwt.expiration-ms", 300000, "blog.auth.cookie.same-site", "Lax"), 1L);
        assertEquals(active, configs.getLong("jwt.expiration-ms"));
        assertEquals(active / 1000, cookies.getExpiresInSeconds());
        var row = configs.list().stream().filter(r -> r.get("key").equals("jwt.expiration-ms")).findFirst().orElseThrow();
        assertEquals(300000L, row.get("value"));
        assertEquals(true, row.get("restartRequired"));
        assertEquals(300000L, instance(environment).getLong("jwt.expiration-ms"));
        var jwt = decoder.decode(tokens.generateToken(seedUser(unique("sys_ttl_"), "TTL 用户", "USER"), List.of("ROLE_USER")));
        assertEquals(active / 1000, jwt.getExpiresAt().getEpochSecond() - jwt.getIssuedAt().getEpochSecond());
    }

    @Test
    void failedRuntimePrerequisiteRollsBackEarlierWritesAndAudit() {
        long before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_config_audit", Long.class);
        var changes = new java.util.LinkedHashMap<String, Object>();
        changes.put("blog.upload-daily-limit", 12);
        changes.put("blog.upload.storage", "MINIO");
        assertThrows(com.zer0drv.blog.common.exception.BusinessException.class, () -> configs.update(changes, 1L));
        assertEquals(before, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_config_audit", Long.class));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_config WHERE config_value IS NOT NULL", Integer.class));
    }

    @Test
    void concurrentAdministratorsSerializeDatabaseOldValuesAndDoNotLoseAudit() throws Exception {
        var other = instance(environment);
        long before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_config_audit", Long.class);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = executor.submit(() -> configs.update(Map.of("blog.upload-daily-limit", 11), 1L));
            var two = executor.submit(() -> other.update(Map.of("blog.upload-daily-limit", 12), 2L));
            one.get(10, TimeUnit.SECONDS); two.get(10, TimeUnit.SECONDS);
        }
        assertEquals(before + 2, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_config_audit", Long.class));
        var rows = jdbcTemplate.queryForList("SELECT old_value,new_value FROM sys_config_audit ORDER BY id DESC LIMIT 2");
        assertEquals(rows.get(1).get("new_value"), rows.get(0).get("old_value"));
    }

    @Test
    void insecureCookieAndUnavailableStorageAreRejectedAtStartupAndDuringWrites() {
        var insecure = new MockEnvironment().withProperty("AUTH_COOKIE_SAME_SITE", "None").withProperty("blog.auth.cookie.secure", "false");
        assertThrows(Exception.class, () -> instance(insecure));
        var safe = instance(new MockEnvironment().withProperty("blog.auth.cookie.secure", "false"));
        assertThrows(com.zer0drv.blog.common.exception.BusinessException.class,
                () -> safe.update(Map.of("blog.auth.cookie.same-site", "None"), 1L));
        jdbcTemplate.update("UPDATE sys_config SET config_value='\"MINIO\"' WHERE config_key='blog.upload.storage'");
        assertThrows(Exception.class, () -> instance(new MockEnvironment().withProperty("blog.minio.enabled", "false")));
    }
}
