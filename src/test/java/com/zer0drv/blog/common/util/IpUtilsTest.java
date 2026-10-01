package com.zer0drv.blog.common.util;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * IpUtils 纯单测（#4）：默认不信任 XFF（伪造无效），开启信任后才解析首跳。
 *
 * @author Yoruhaki
 */
class IpUtilsTest {

    @AfterEach
    void resetTrustFlag() {
        IpUtils.setTrustProxyHeaders(false);
    }

    @Test
    void clientIp_defaultIgnoresForgedXff() {
        IpUtils.setTrustProxyHeaders(false);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("6.6.6.6");
        when(request.getRemoteAddr()).thenReturn("1.2.3.4");

        assertEquals("1.2.3.4", IpUtils.clientIp(request));
    }

    @Test
    void clientIp_trustedProxyUsesXffFirstHop() {
        IpUtils.setTrustProxyHeaders(true);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("5.6.7.8, 10.0.0.1");
        when(request.getRemoteAddr()).thenReturn("1.2.3.4");

        assertEquals("5.6.7.8", IpUtils.clientIp(request));
    }

    @Test
    void clientIp_trustedProxyWithoutXffFallsBackToRemoteAddr() {
        IpUtils.setTrustProxyHeaders(true);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("1.2.3.4");

        assertEquals("1.2.3.4", IpUtils.clientIp(request));
    }
}
