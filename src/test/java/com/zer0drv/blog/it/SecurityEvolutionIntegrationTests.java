package com.zer0drv.blog.it;

import com.zer0drv.blog.admin.service.*;
import com.zer0drv.blog.auth.service.*;
import com.zer0drv.blog.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.http.HttpHeaders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SecurityEvolutionIntegrationTests extends IntegrationTestSupport {
    @Autowired RefreshSessionService sessions;
    @Autowired JwtDecoder decoder;
    @Autowired OwnerOperations owners;
    @Autowired OwnerRecoveryService recovery;
    @Autowired ApplicationRestartService restart;
    @Autowired TokenService tokens;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;

    @Test void logoutRevokesRotatedFamilyButKeepsOtherDevice() throws Exception {
        String name = unique("logout_family_"); seedUser(name,"用户","USER");
        var original = decoder.decode(login(name, TEST_PASSWORD));
        String refresh = sessions.create(original);
        var rotated = sessions.rotate(refresh);
        String rotatedJti = decoder.decode(rotated.access()).getId();
        var other = decoder.decode(login(name, TEST_PASSWORD));
        String otherRefresh = sessions.create(other);
        tokens.blackToken(original.getId(), original.getExpiresAt());
        org.mockito.Mockito.verify(redis.opsForValue()).set(
                org.mockito.ArgumentMatchers.eq(com.zer0drv.blog.auth.validator.BlacklistJwtValidator.BLACKLIST_PREFIX + rotatedJti),
                org.mockito.ArgumentMatchers.eq("1"), org.mockito.ArgumentMatchers.any(java.time.Duration.class));
        assertThrows(BusinessException.class, () -> sessions.rotate(rotated.refresh()));
        assertNotNull(sessions.rotate(otherRefresh));
    }

    @Test void refreshRotatesAndReuseRevokesEveryCredential() throws Exception {
        String name = unique("refresh_"); long user = seedUser(name,"用户","USER");
        String access = login(name, TEST_PASSWORD);
        var jwt = decoder.decode(access);
        assertTrue(java.time.Duration.between(jwt.getIssuedAt(),jwt.getExpiresAt()).toSeconds() <= 900);
        String old = sessions.create(jwt);
        var rotation = sessions.rotate(old);
        assertNotEquals(old, rotation.refresh());
        assertNotNull(decoder.decode(rotation.access()));
        assertThrows(BusinessException.class, () -> sessions.rotate(old));
        assertThrows(Exception.class, () -> decoder.decode(rotation.access()));
        assertThrows(BusinessException.class, () -> sessions.rotate(rotation.refresh()));
        assertTrue(jdbcTemplate.queryForObject("SELECT session_version FROM `user` WHERE id=?",Long.class,user) > 0);
    }

    @Test void concurrentRefreshHasOneWinnerAndReuseInvalidatesIt() throws Exception {
        String name = unique("race_"); seedUser(name,"用户","USER");
        String old = sessions.create(decoder.decode(login(name, TEST_PASSWORD)));
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> attempt = () -> {
                start.await();
                try { sessions.rotate(old); return true; } catch (BusinessException rejected) { return false; }
            };
            var first = pool.submit(attempt); var second = pool.submit(attempt); start.countDown();
            assertNotEquals(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test void ordinaryAdminCannotInvokeOwnerOrModifyOwner() throws Exception {
        String name = unique("admin_"); seedUser(name,"管理员","ADMIN");
        long owner = seedUser(unique("owner_"),"所有者","SUPER_ADMIN");
        String bearer = bearerOf(name);
        mockMvc.perform(post("/admin/owner/users/{id}", owner).header(HttpHeaders.AUTHORIZATION,bearer)
                .contentType("application/json").content("{}" )).andExpect(status().isForbidden());
        mockMvc.perform(put("/admin/users/{id}/ban",owner).header(HttpHeaders.AUTHORIZATION,bearer))
                .andExpect(jsonPath("$.code").value("40061"));
        mockMvc.perform(put("/admin/users/{id}/role",owner).header(HttpHeaders.AUTHORIZATION,bearer)
                .contentType("application/json").content("{\"role\":\"SUPER_ADMIN\"}"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not("200")));
    }

    @Test void ownerReauthFreezeAndIndependentOwnershipTransfer() throws Exception {
        String ownerName = unique("owner_"); long owner = seedUser(ownerName,"所有者","SUPER_ADMIN");
        String adminName = unique("admin_"); long target = seedUser(adminName,"管理员","ADMIN");
        String oldAccess = login(adminName,TEST_PASSWORD);
        assertThrows(BusinessException.class, () -> owners.execute(owner,target,new OwnerOperations.Request("FREEZE","wrong",null,adminName,null)));
        owners.execute(owner,target,new OwnerOperations.Request("FREEZE",TEST_PASSWORD,null,adminName,null));
        assertThrows(Exception.class, () -> decoder.decode(oldAccess));
        assertEquals(1,jdbcTemplate.queryForObject("SELECT status FROM `user` WHERE id=?",Integer.class,target));
        owners.execute(owner,target,new OwnerOperations.Request("UNFREEZE",TEST_PASSWORD,null,adminName,null));
        owners.execute(owner,target,new OwnerOperations.Request("TRANSFER",TEST_PASSWORD,null,adminName,null));
        assertEquals("SUPER_ADMIN",jdbcTemplate.queryForObject("SELECT role FROM `user` WHERE id=?",String.class,target));
        assertEquals("ADMIN",jdbcTemplate.queryForObject("SELECT role FROM `user` WHERE id=?",String.class,owner));
        assertThrows(BusinessException.class, () -> owners.execute(target,target,new OwnerOperations.Request("FREEZE",TEST_PASSWORD,null,adminName,null)));
        assertEquals(0,jdbcTemplate.queryForObject("SELECT status FROM `user` WHERE id=?",Integer.class,target));
    }

    @Test void localRecoveryCannotPromoteAnUnrelatedAccountAndRotatesCredentials() throws Exception {
        String user = unique("recover_user_"); seedUser(user,"用户","USER");
        assertThrows(IllegalArgumentException.class, () -> recovery.recover(user,"recovery-password-123",false));
        String owner = unique("recover_owner_"); seedUser(owner,"所有者","SUPER_ADMIN");
        String old = login(owner,TEST_PASSWORD);
        recovery.recover(owner,"recovery-password-123",false);
        assertThrows(Exception.class, () -> decoder.decode(old));
        assertNotNull(login(owner,"recovery-password-123"));
    }

    @Test void noWorkerMeansUnavailableWithoutRestart() {
        assertEquals(false,restart.capability().get("available"));
    }
}
