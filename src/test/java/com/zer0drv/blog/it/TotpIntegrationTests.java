package com.zer0drv.blog.it;

import com.zer0drv.blog.auth.service.TotpService;
import com.zer0drv.blog.auth.service.TotpCrypto;
import com.zer0drv.blog.auth.service.AuthService;
import com.zer0drv.blog.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TotpIntegrationTests extends IntegrationTestSupport {
    @Autowired TotpService totp;
    @Autowired AuthService auth;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    private record Account(long id, String username, String bearer, String secret, List<String> recovery) {}
    private String code(String secret) { return TotpCrypto.code(secret, Instant.now().getEpochSecond() / 30, 6); }
    private Account enabledAccount() throws Exception {
        String username = unique("totp_"); long id = seedUser(username, "Two factor", "USER");
        String bearer = bearerOf(username);
        String secret = totp.setup(id, TEST_PASSWORD).get("secret");
        var codes = totp.confirm(id, TEST_PASSWORD, code(secret));
        return new Account(id, username, bearer, secret, codes);
    }
    @Test void passwordOnlyReturnsChallengeWithoutCookieOrTokenThenRecoveryLogsInOnce() throws Exception {
        var a = enabledAccount();
        var result = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", a.username, "password", TEST_PASSWORD))))
                .andExpect(jsonPath("$.data.mfa_required").value("true"))
                .andExpect(jsonPath("$.data.access_token").doesNotExist()).andReturn();
        assertTrue(result.getResponse().getHeaders("Set-Cookie").stream().allMatch(c -> c.contains("Max-Age=0")));
        String challenge = objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("challenge").asText();
        mockMvc.perform(get("/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + challenge)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/auth/totp/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("challenge", challenge, "code", a.recovery.getFirst()))))
                .andExpect(jsonPath("$.code").value("200")).andExpect(jsonPath("$.data.access_token").isNotEmpty())
                .andExpect(header().exists("Set-Cookie"));
        assertThrows(BusinessException.class, () -> totp.complete(challenge, a.recovery.get(1)));
        assertEquals(9, totp.status(a.id).get("recoveryRemaining"));
        assertThrows(BusinessException.class, () -> totp.complete(totp.primary(a.id, null).get("challenge"), a.recovery.getFirst()));
    }
    @Test void setupRequiresPasswordAndValidConfirmationDoesNotStorePlaintext() throws Exception {
        String username = unique("totp_setup_"); long id = seedUser(username, "Setup", "USER");
        assertThrows(BusinessException.class, () -> totp.setup(id, "wrong"));
        var setup = totp.setup(id, TEST_PASSWORD);
        assertTrue(setup.get("uri").contains("issuer=Blog"));
        assertEquals(false, totp.status(id).get("enabled"));
        assertNotEquals(setup.get("secret"), jdbcTemplate.queryForObject("SELECT secret FROM user_totp WHERE user_id=?", String.class, id));
        assertThrows(BusinessException.class, () -> totp.confirm(id, TEST_PASSWORD, "invalid"));
        assertEquals(false, totp.status(id).get("enabled"));
        var recovery = totp.confirm(id, TEST_PASSWORD, code(setup.get("secret")));
        assertEquals(10, recovery.size());
        assertEquals(10, recovery.stream().distinct().count());
        var hashes = jdbcTemplate.queryForList("SELECT code_hash FROM totp_recovery WHERE user_id=?", String.class, id);
        assertTrue(recovery.stream().noneMatch(hashes::contains));
        assertThrows(BusinessException.class, () -> totp.setup(id, TEST_PASSWORD));
    }
    @Test void replayAndFailuresPersistAcrossNewChallenges() throws Exception {
        var a = enabledAccount();
        for (int i = 0; i < 5; i++) {
            String challenge = totp.primary(a.id, null).get("challenge");
            assertThrows(BusinessException.class, () -> totp.complete(challenge, "invalid"));
        }
        assertThrows(BusinessException.class, () -> totp.primary(a.id, null));
        assertTrue(jdbcTemplate.queryForObject("SELECT blocked_until FROM user_totp WHERE user_id=?", Long.class, a.id) > Instant.now().getEpochSecond());
    }
    @Test void totpCodeCannotBeReusedAndFutureOutsideWindowRejected() throws Exception {
        var a = enabledAccount();
        long consumed = jdbcTemplate.queryForObject("SELECT last_step FROM user_totp WHERE user_id=?", Long.class, a.id);
        String challenge = totp.primary(a.id, null).get("challenge");
        // The confirmation just consumed the current time step.
        assertThrows(BusinessException.class, () -> totp.complete(challenge, TotpCrypto.code(a.secret, consumed, 6)));
        String future = TotpCrypto.code(a.secret, Instant.now().getEpochSecond() / 30 + 10, 6);
        assertThrows(BusinessException.class, () -> totp.complete(challenge, future));
        // Make a previous use old enough without waiting on wall-clock time.
        jdbcTemplate.update("UPDATE user_totp SET last_step=-1 WHERE user_id=?", a.id);
        assertNotNull(totp.complete(challenge, code(a.secret)).get("access_token"));
        String nextChallenge = totp.primary(a.id, null).get("challenge");
        assertThrows(BusinessException.class, () -> totp.complete(nextChallenge, code(a.secret)));
    }
    @Test void expiryPasswordChangeBanAndRoleChangeInvalidateChallenge() throws Exception {
        var a = enabledAccount();
        String challenge = totp.primary(a.id, null).get("challenge");
        jdbcTemplate.update("UPDATE totp_challenge SET expires_at=0 WHERE user_id=?", a.id);
        assertThrows(BusinessException.class, () -> totp.complete(challenge, a.recovery.getFirst()));
        String changed = totp.primary(a.id, null).get("challenge");
        jdbcTemplate.update("UPDATE `user` SET password=? WHERE id=?", "changed", a.id);
        assertThrows(BusinessException.class, () -> totp.complete(changed, a.recovery.getFirst()));
        assertEquals(true, totp.status(a.id).get("enabled"));
        String role = totp.primary(a.id, null).get("challenge");
        jdbcTemplate.update("UPDATE `user` SET role='AUTHOR' WHERE id=?", a.id);
        assertThrows(BusinessException.class, () -> totp.complete(role, a.recovery.getFirst()));
        String banned = totp.primary(a.id, null).get("challenge");
        jdbcTemplate.update("UPDATE `user` SET status=1 WHERE id=?", a.id);
        assertThrows(BusinessException.class, () -> totp.complete(banned, a.recovery.getFirst()));
    }
    @Test void regenerationInvalidatesOldCodesAndDisableRequiresBothFactors() throws Exception {
        var a = enabledAccount();
        assertThrows(BusinessException.class, () -> totp.disable(a.id, "wrong", a.recovery.getFirst()));
        var regenerated = totp.regenerate(a.id, TEST_PASSWORD, a.recovery.getFirst());
        String challenge = totp.primary(a.id, null).get("challenge");
        assertThrows(BusinessException.class, () -> totp.complete(challenge, a.recovery.get(1)));
        totp.disable(a.id, TEST_PASSWORD, regenerated.getFirst());
        assertEquals(false, totp.status(a.id).get("enabled"));
        assertEquals(0, totp.status(a.id).get("recoveryRemaining"));
        assertNotNull(totp.primary(a.id, null).get("access_token"));
        assertThrows(BusinessException.class, () -> totp.complete(challenge, regenerated.get(1)));
    }
    @Test void concurrentCompletionHasOnlyOneWinner() throws Exception {
        var a = enabledAccount(); String challenge = totp.primary(a.id, null).get("challenge");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(i -> executor.submit(() -> {
                try { totp.complete(challenge, a.recovery.getFirst()); return true; }
                catch (BusinessException e) { return false; }
            })).toList();
            int successes = 0;
            for (var task : tasks) if (task.get()) successes++;
            assertEquals(1, successes);
        }
        assertEquals(9, totp.status(a.id).get("recoveryRemaining"));
    }
    @Test void githubLoginCannotBypassTotpAndAnonymousCannotManageIt() throws Exception {
        var a = enabledAccount();
        jdbcTemplate.update("UPDATE `user` SET github_id=? WHERE id=?", a.id + 100000, a.id);
        var result = auth.loginByGithub(a.id + 100000, "oauth", "OAuth", "", a.username + "@it.local");
        assertEquals("true", result.get("mfa_required")); assertFalse(result.containsKey("access_token"));
        mockMvc.perform(get("/auth/totp")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/auth/totp/setup").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/auth/totp").header(HttpHeaders.AUTHORIZATION, a.bearer))
                .andExpect(jsonPath("$.data.secret").doesNotExist()).andExpect(jsonPath("$.data.recoveryCodes").doesNotExist());
    }
    @Test void actualEmailPasswordResetPreservesTotpAndInvalidatesPendingChallenge() throws Exception {
        var a = enabledAccount(); String challenge = totp.primary(a.id, null).get("challenge");
        String email = a.username + "@it.local";
        org.mockito.Mockito.when(redis.opsForValue().get("blog:email-code:password_reset:" + email)).thenReturn("654321");
        mockMvc.perform(post("/auth/password-reset").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "code", "654321", "newPassword", "New-password-1"))))
                .andExpect(jsonPath("$.code").value("200"));
        assertEquals(true, totp.status(a.id).get("enabled"));
        assertThrows(BusinessException.class, () -> totp.complete(challenge, a.recovery.getFirst()));
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", a.username, "password", "New-password-1"))))
                .andExpect(jsonPath("$.data.mfa_required").value("true")).andExpect(jsonPath("$.data.access_token").doesNotExist());
    }
    @Test void oauthExchangeReturnsChallengeWithoutEstablishingCookie() throws Exception {
        org.mockito.Mockito.when(redis.opsForValue().getAndDelete("oauth:code:totp-test-code"))
                .thenReturn("mfa:challenge-value");
        mockMvc.perform(post("/auth/oauth/exchange").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"totp-test-code\"}"))
                .andExpect(jsonPath("$.data.challenge").value("challenge-value"))
                .andExpect(jsonPath("$.data.mfa_required").value("true"))
                .andExpect(jsonPath("$.data.expiresIn").doesNotExist());
    }
    @Test void expiredSetupCannotEnableAndWrongPasswordCounterIsDurable() {
        long id = seedUser(unique("expired_totp_"), "Expired setup", "USER");
        String secret = totp.setup(id, TEST_PASSWORD).get("secret");
        jdbcTemplate.update("UPDATE user_totp SET setup_expires=0 WHERE user_id=?", id);
        assertThrows(BusinessException.class, () -> totp.confirm(id, TEST_PASSWORD, code(secret)));
        assertEquals(false, totp.status(id).get("enabled"));
        for (int i = 0; i < 5; i++) assertThrows(BusinessException.class, () -> totp.setup(id, "wrong"));
        assertThrows(BusinessException.class, () -> totp.setup(id, TEST_PASSWORD));
    }
}
