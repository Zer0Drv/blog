package com.zer0drv.blog.auth.controller;

import com.zer0drv.blog.auth.service.AuthCookieService;
import com.zer0drv.blog.auth.service.RefreshSessionService;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.Result;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class RefreshController {
    private final RefreshSessionService sessions;
    private final AuthCookieService cookies;
    @PostMapping("/auth/refresh")
    public org.springframework.http.ResponseEntity<?> refresh(
            @CookieValue(name="REFRESH_TOKEN", required=false) String token, HttpServletResponse response) {
        try {
            var rotation = sessions.rotate(token);
            cookies.writeAccessCookie(response, rotation.access());
            cookies.writeRefreshCookie(response, rotation.refresh(), rotation.expires() - Instant.now().getEpochSecond());
            return org.springframework.http.ResponseEntity.ok(Result.ok(Map.of("refreshed", true)));
        } catch (BusinessException invalid) {
            cookies.clearTokenCookie(response);
            return org.springframework.http.ResponseEntity.status(401).body(Map.of("code", "40100", "message", "会话已失效，请重新登录"));
        }
    }
}
