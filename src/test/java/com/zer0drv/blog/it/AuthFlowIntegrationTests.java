package com.zer0drv.blog.it;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证链路接口级集成测试：预置用户 → 真实登录拿 JWT → /auth/me 断言资料；
 * 错误密码按业务码 40002 断言；无 token 访问受保护接口由安全链直接 401。
 *
 * @author Yoruhaki
 */
class AuthFlowIntegrationTests extends IntegrationTestSupport {

    @Test
    void loginThenGetMeReturnsProfile() throws Exception {
        String username = unique("it_auth_ok_");
        String nickname = unique("集成昵称");
        seedUser(username, nickname, "USER");

        String token = login(username, TEST_PASSWORD);

        mockMvc.perform(get("/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.nickname").value(nickname))
                .andExpect(jsonPath("$.data.role").value("USER"));
    }

    @Test
    void loginWithWrongPasswordReturnsBusinessCode() throws Exception {
        String username = unique("it_auth_bad_");
        seedUser(username, "错误密码用户", "USER");

        // 业务异常由 GlobalExceptionHandler 包装为统一响应体（HTTP 200 + 业务码）
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40002"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void getMeWithoutTokenReturns401() throws Exception {
        // 未携带 Bearer：资源服务器入口直接 401（不进入统一响应体）
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized());
    }
}
