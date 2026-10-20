package com.zer0drv.blog.auth;

import com.zer0drv.blog.auth.oauth2.OAuth2LoginSuccessHandler;
import com.zer0drv.blog.auth.oauth2.OAuthCodeStore;
import com.zer0drv.blog.auth.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OAuthTotpTest {
    @Test void challengeTravelsThroughOneTimeExchangeAndOAuthSessionIsRemoved() throws Exception {
        var auth = mock(AuthService.class); var store = mock(OAuthCodeStore.class);
        var handler = new OAuth2LoginSuccessHandler(auth, store);
        ReflectionTestUtils.setField(handler, "successRedirect", "http://localhost/oauth/callback");
        ReflectionTestUtils.setField(handler, "failureRedirect", "http://localhost/login");
        var principal = mock(OAuth2User.class); var authentication = mock(Authentication.class);
        when(principal.getAttributes()).thenReturn(Map.of("id", 42, "login", "alice"));
        when(authentication.getPrincipal()).thenReturn(principal);
        when(auth.loginByGithub(eq(42L), eq("alice"), isNull(), isNull(), isNull()))
                .thenReturn(Map.of("mfa_required", "true", "challenge", "secret-challenge"));
        when(store.store("mfa:secret-challenge")).thenReturn("one-time-code");
        var request = new MockHttpServletRequest(); var session = new MockHttpSession(); request.setSession(session);
        var response = new MockHttpServletResponse();
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            handler.onAuthenticationSuccess(request, response, authentication);
            assertTrue(session.isInvalid());
            assertNull(SecurityContextHolder.getContext().getAuthentication());
            assertEquals("http://localhost/oauth/callback?code=one-time-code", response.getRedirectedUrl());
            assertFalse(response.getRedirectedUrl().contains("secret-challenge"));
            assertNull(response.getHeader("Set-Cookie"));
        } finally { SecurityContextHolder.clearContext(); }
    }
}
