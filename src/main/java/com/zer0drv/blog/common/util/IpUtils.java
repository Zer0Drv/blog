package com.zer0drv.blog.common.util;

import jakarta.servlet.http.HttpServletRequest;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 客户端 IP 提取：优先取 X-Forwarded-For 首跳（反向代理场景），否则取 remoteAddr。
 *
 * @author Yoruhaki
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class IpUtils {

    /**
     * 取客户端 IP：X-Forwarded-For 首跳 → remoteAddr
     */
    public static String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
