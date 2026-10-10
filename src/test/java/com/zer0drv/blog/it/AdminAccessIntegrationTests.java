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

    /**
     * 回归：库内直改提拔为 ADMIN 后，旧 token（roles 声明仍 ROLE_USER）立即获得访问权，
     * 不再误返 403——权限由 DatabaseRoleJwtAuthenticationConverter 按 sub 实时读库装配
     */
    @Test
    void staleTokenAfterPromotionToAdminReturns200() throws Exception {
        String username = unique("it_admin_promote_");
        long userId = seedUser(username, "待提拔", "USER");
        String staleBearer = bearerOf(username);

        jdbcTemplate.update("UPDATE `user` SET role = 'ADMIN' WHERE id = ?", userId);

        mockMvc.perform(get("/admin/stats/overview")
                        .header(HttpHeaders.AUTHORIZATION, staleBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
    }

    /**
     * 回归的反向：库内直改降级后，旧 token（roles 声明仍 ROLE_ADMIN）立即失效，
     * 不得凭过期声明继续放行
     */
    @Test
    void staleTokenAfterDemotionFromAdminReturns403() throws Exception {
        String username = unique("it_admin_demote_");
        long userId = seedUser(username, "待降级", "ADMIN");
        String staleBearer = bearerOf(username);

        jdbcTemplate.update("UPDATE `user` SET role = 'USER' WHERE id = ?", userId);

        mockMvc.perform(get("/admin/stats/overview")
                        .header(HttpHeaders.AUTHORIZATION, staleBearer))
                .andExpect(status().isForbidden());
    }
}
