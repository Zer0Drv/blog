package com.zer0drv.blog.subscription.controller;
import com.zer0drv.blog.subscription.service.SubscriptionService;
import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.common.util.JwtSubjects;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class SubscriptionController {
    private final SubscriptionService subscriptions;
    public record Toggle(@NotNull Boolean enabled) {}
    public record Code(@Pattern(regexp="[0-9]{6}") @NotNull String code) {}
    public record Unsubscribe(@Pattern(regexp="[a-f0-9]{32}") @NotNull String token) {}
    @GetMapping("/subscriptions/me")
    public Result<Map<String, Boolean>> state(@AuthenticationPrincipal Jwt jwt) { return Result.ok(subscriptions.state(JwtSubjects.userIdOf(jwt))); }
    @PutMapping("/subscriptions/me")
    public Result<Void> set(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid Toggle dto) { subscriptions.set(JwtSubjects.userIdOf(jwt), dto.enabled()); return Result.ok(); }
    @PostMapping("/subscriptions/email-code")
    public Result<Void> sendCode(@AuthenticationPrincipal Jwt jwt) { subscriptions.sendEmailCode(JwtSubjects.userIdOf(jwt)); return Result.ok(); }
    @PostMapping("/subscriptions/email-confirm")
    public Result<Void> confirm(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid Code dto) { subscriptions.confirmEmail(JwtSubjects.userIdOf(jwt), dto.code()); return Result.ok(); }
    @DeleteMapping("/subscriptions/email")
    public Result<Void> stopEmail(@AuthenticationPrincipal Jwt jwt) { subscriptions.stopEmail(JwtSubjects.userIdOf(jwt)); return Result.ok(); }
    @PostMapping("/subscriptions/unsubscribe")
    public Result<Void> unsubscribe(@RequestBody @Valid Unsubscribe dto) { subscriptions.unsubscribe(dto.token()); return Result.ok(); }
    @GetMapping("/admin/subscriptions/stats")
    public Result<Map<String, Object>> stats() { return Result.ok(subscriptions.stats()); }
}
