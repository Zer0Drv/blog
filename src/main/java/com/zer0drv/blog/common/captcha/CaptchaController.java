package com.zer0drv.blog.common.captcha;

import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.common.util.IpUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 图形验证码端点（匿名可访问，SecurityConfig 已放行）。
 *
 * @author Yoruhaki
 */
@RestController
@RequestMapping("/auth/captcha")
@RequiredArgsConstructor
public class CaptchaController {

    private final CaptchaService captchaService;

    /**
     * 生成图形验证码：{ "captchaId": "uuid", "imageBase64": "data:image/png;base64,..." }
     */
    @GetMapping
    public Result<Map<String, String>> captcha(@RequestParam String scene) {
        return Result.ok(captchaService.generate(scene));
    }

    /**
     * 当前调用方（按 IP）在该场景是否必须先过图形验证码；comment 场景前端不预检
     */
    @GetMapping("/required")
    public Result<Map<String, Boolean>> required(@RequestParam String scene, HttpServletRequest request) {
        return Result.ok(Map.of("required", captchaService.isRequired(scene, IpUtils.clientIp(request))));
    }
}
