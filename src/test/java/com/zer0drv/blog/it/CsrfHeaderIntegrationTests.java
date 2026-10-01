package com.zer0drv.blog.it;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CSRF 头检查接口级集成测试（blog-ui#13 契约第 6 条）：开启检查后，
 * mutating 请求缺 X-Requested-With → 403；携带 → 放行；认证入口豁免。
 * （主测试 yaml 默认关闭该检查，本类用 properties 单独开启一个上下文验证。）
 *
 * @author Yoruhaki
 */
@SpringBootTest(properties = "blog.security.csrf-header-check-enabled=true")
class CsrfHeaderIntegrationTests extends IntegrationTestSupport {

    @Test
    void mutatingRequestWithoutXrwHeaderReturns403() throws Exception {
        String username = unique("it_csrf_block_");
        seedUser(username, "CSRF拦截", "USER");

        mockMvc.perform(put("/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(username))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"拦截前昵称\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void mutatingRequestWithXrwHeaderPasses() throws Exception {
        String username = unique("it_csrf_pass_");
        seedUser(username, "CSRF放行", "USER");

        mockMvc.perform(put("/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(username))
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"放行后昵称\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.nickname").value("放行后昵称"));
    }

    @Test
    void loginEntrypointIsExemptFromXrwCheck() throws Exception {
        String username = unique("it_csrf_login_");
        seedUser(username, "CSRF豁免", "USER");

        // 登录属认证入口，豁免 X-Requested-With 检查（login() 助手不带该头）
        String token = login(username, TEST_PASSWORD);
        assertFalse(token.isBlank());
    }
}
