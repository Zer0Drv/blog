package com.zer0drv.blog.it;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 个人资料接口级集成测试：登录后 PUT /users/me 改昵称 / 简介 / 头像，
 * 断言响应体与随后的 GET /auth/me 均已更新；昵称留空触发参数校验业务码 40023。
 *
 * @author Yoruhaki
 */
class ProfileIntegrationTests extends IntegrationTestSupport {

    @Test
    void updateProfileThenMeReflectsChanges() throws Exception {
        String username = unique("it_profile_");
        seedUser(username, "旧昵称", "USER");
        String bearer = bearerOf(username);

        String newNickname = unique("新昵称");
        String newBio = "集成测试简介：" + newNickname;
        String newAvatar = "https://img.it.local/avatar.png";

        mockMvc.perform(put("/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + newNickname + "\",\"bio\":\"" + newBio
                                + "\",\"avatar\":\"" + newAvatar + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.nickname").value(newNickname))
                .andExpect(jsonPath("$.data.bio").value(newBio))
                .andExpect(jsonPath("$.data.avatar").value(newAvatar));

        mockMvc.perform(get("/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value(newNickname))
                .andExpect(jsonPath("$.data.bio").value(newBio))
                .andExpect(jsonPath("$.data.avatar").value(newAvatar));
    }

    @Test
    void updateProfileWithBlankNicknameFailsValidation() throws Exception {
        String username = unique("it_profile_bad_");
        seedUser(username, "资料用户", "USER");
        String bearer = bearerOf(username);

        mockMvc.perform(put("/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"\",\"bio\":\"任意简介\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40023"));
    }
}
