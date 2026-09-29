package com.zer0drv.blog.it;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理后台鉴权接口级集成测试：/admin/** 由安全链 URL 级规则 hasRole(ADMIN) 拦截，
 * USER 角色 → 真实 HTTP 403，匿名 → 401，ADMIN → 200。
 *
 * @author Yoruhaki
 */
class AdminAccessIntegrationTests extends IntegrationTestSupport {

    @Test
    void userRoleAccessingAdminStatsReturns403() throws Exception {
        String username = unique("it_admin_user_");
        seedUser(username, "普通用户", "USER");

        mockMvc.perform(get("/admin/stats/overview")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(username)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousAccessingAdminStatsReturns401() throws Exception {
        mockMvc.perform(get("/admin/stats/overview"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminRoleAccessingAdminStatsReturns200() throws Exception {
        String username = unique("it_admin_root_");
        seedUser(username, "管理员", "ADMIN");

        mockMvc.perform(get("/admin/stats/overview")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data").exists());
    }
}
