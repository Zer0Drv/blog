package com.zer0drv.blog.common.util;

import jakarta.servlet.http.HttpServletRequest;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 客户端 IP 提取（#4）：默认一律取 remoteAddr（伪造 X-Forwarded-For 无法绕过验证码/限流）；
 * 仅当明确配置 blog.security.trust-proxy-headers=true（应用处于可信反向代理后）时才解析 XFF 首跳。
 * 反代部署时建议同时配置 server.forward-headers-strategy: framework（详见 README）。
 *
 * @author Yoruhaki
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class IpUtils {

    /**
     * 是否信任代理头，由 TrustProxyConfig 按 blog.security.trust-proxy-headers 注入，默认 false
     */
    private static volatile boolean trustProxyHeaders = false;

    /**
     * 注入代理头信任开关（启动期由 TrustProxyConfig 调用一次）
     */
    public static void setTrustProxyHeaders(boolean trust) {
        trustProxyHeaders = trust;
    }

    /**
     * 取客户端 IP：信任代理头时取 X-Forwarded-For 首跳，否则取 remoteAddr
     */
    public static String clientIp(HttpServletRequest request) {
        if (trustProxyHeaders) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                return forwardedFor.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
