package com.zer0drv.blog.it;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 站点设置中心接口级集成测试：公开接口只出白名单 5 键；admin 读写全量、未知键忽略。
 *
 * @author Yoruhaki
 */
class SiteConfigIntegrationTests extends IntegrationTestSupport {

    @Test
    void publicConfigOnlyExposesWhitelistKeys() throws Exception {
        mockMvc.perform(get("/site/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data['site.name']").value("Blog"))
                .andExpect(jsonPath("$.data['site.description']").value("记录与分享"))
                .andExpect(jsonPath("$.data['site.logo']").exists())
                .andExpect(jsonPath("$.data['site.icp']").exists())
                .andExpect(jsonPath("$.data['site.footer']").exists())
                // base_url 与审核开关不外泄
                .andExpect(jsonPath("$.data['site.base_url']").doesNotExist())
                .andExpect(jsonPath("$.data['comment.review_required']").doesNotExist());
    }

    @Test
    void adminReadWriteAndUnknownKeysIgnored() throws Exception {
        String adminName = unique("it_site_admin_");
        seedUser(adminName, "站点管理员", "ADMIN");
        String adminBearer = bearerOf(adminName);

        // 全量读：含非公开键
        mockMvc.perform(get("/admin/site/config")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data['site.base_url']").value("http://localhost:5173"))
                .andExpect(jsonPath("$.data['comment.review_required']").value("false"));

        // 写：已知键生效、未知键忽略；返回最新全量
        String newName = unique("集成站点名");
        mockMvc.perform(put("/admin/site/config")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"site.name\":\"" + newName + "\",\"unknown.key\":\"hack\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data['site.name']").value(newName))
                .andExpect(jsonPath("$.data['unknown.key']").doesNotExist());

        // 缓存已刷新：公开接口立即读到新值
        mockMvc.perform(get("/site/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data['site.name']").value(newName));

        // 恢复种子值，避免污染共享 H2 库中的其他测试
        mockMvc.perform(put("/admin/site/config")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"site.name\":\"Blog\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void nonAdminCannotWriteSiteConfig() throws Exception {
        String username = unique("it_site_user_");
        seedUser(username, "普通用户", "USER");

        mockMvc.perform(put("/admin/site/config")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(username))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"site.name\":\"x\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/admin/site/config")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(username)))
                .andExpect(status().isForbidden());
    }
}
