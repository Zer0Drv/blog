package com.zer0drv.blog.auth.controller;

import com.zer0drv.blog.auth.service.AuthCookieService;
import com.zer0drv.blog.auth.service.TotpService;
import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.common.util.JwtSubjects;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/auth/totp")
@RequiredArgsConstructor
public class TotpController {
    private final TotpService totp;
    private final AuthCookieService cookies;
    public record Setup(@NotBlank @Size(max=64) String password) {}
    public record Proof(@NotBlank @Size(max=64) String password, @NotBlank @Size(max=32) String code) {}
    public record Login(@NotBlank @Size(max=64) String challenge, @NotBlank @Size(max=32) String code) {}

    @GetMapping public Result<Map<String, Object>> status(@AuthenticationPrincipal Jwt jwt) {
        return Result.ok(totp.status(JwtSubjects.userIdOf(jwt)));
    }
    @PostMapping("/setup") public Result<Map<String, String>> setup(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Setup dto) {
        return Result.ok(totp.setup(JwtSubjects.userIdOf(jwt), dto.password()));
    }
    @PostMapping("/confirm") public Result<List<String>> confirm(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Proof dto, HttpServletResponse response) {
        var codes = totp.confirm(JwtSubjects.userIdOf(jwt), dto.password(), dto.code());
        cookies.clearTokenCookie(response); return Result.ok(codes);
    }
    @PostMapping("/disable") public Result<Void> disable(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Proof dto, HttpServletResponse response) {
        totp.disable(JwtSubjects.userIdOf(jwt), dto.password(), dto.code());
        cookies.clearTokenCookie(response); return Result.ok();
    }
    @PostMapping("/recovery") public Result<List<String>> recovery(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Proof dto, HttpServletResponse response) {
        var codes = totp.regenerate(JwtSubjects.userIdOf(jwt), dto.password(), dto.code());
        cookies.clearTokenCookie(response); return Result.ok(codes);
    }
    @PostMapping("/login") public Result<Map<String, String>> login(@Valid @RequestBody Login dto, HttpServletResponse response) {
        var result = totp.complete(dto.challenge(), dto.code());
        cookies.writeTokenCookie(response, result.get("access_token")); return Result.ok(result);
    }
}
