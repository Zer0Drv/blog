package com.zer0drv.blog.admin.controller;

import com.zer0drv.blog.admin.service.OwnerOperations;
import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.common.util.JwtSubjects;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/owner")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class OwnerOperationsController {
    private final OwnerOperations operations;
    @PostMapping("/users/{id}")
    public Result<Void> execute(@PathVariable long id, @AuthenticationPrincipal Jwt jwt, @RequestBody OwnerOperations.Request request) {
        operations.execute(JwtSubjects.userIdOf(jwt), id, request);
        return Result.ok();
    }
}
