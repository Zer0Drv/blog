package com.zer0drv.blog.auth.controller;

import com.zer0drv.blog.auth.service.RefreshSessionService;
import com.zer0drv.blog.common.response.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/auth/sessions")
@RequiredArgsConstructor
public class SessionDeviceController {
    private final RefreshSessionService sessions;

    public record Proof(@NotBlank @Size(max = 64) String password, @Size(max = 32) String code) {}
    public record Revoked(int count) {}

    @GetMapping
    public Result<List<RefreshSessionService.Device>> list(@AuthenticationPrincipal Jwt jwt) {
        return Result.ok(sessions.devices(jwt));
    }

    @PostMapping("/{id}/revoke")
    public Result<Revoked> revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @Valid @RequestBody Proof proof) {
        return Result.ok(new Revoked(sessions.revokeDevices(jwt, id, proof.password(), proof.code())));
    }

    @PostMapping("/others/revoke")
    public Result<Revoked> revokeOthers(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Proof proof) {
        return Result.ok(new Revoked(sessions.revokeDevices(jwt, null, proof.password(), proof.code())));
    }
}
