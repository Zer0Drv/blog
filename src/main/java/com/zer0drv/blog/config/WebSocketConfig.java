package com.zer0drv.blog.config;

import com.zer0drv.blog.auth.service.AuthCookieService;
import com.zer0drv.blog.social.ws.NotifyWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.List;
import java.util.Map;

/**
 * WebSocket 配置：把 NotifyWebSocketHandler 注册到 /ws（私信+通知实时推送）。
 * 握手鉴权（blog-ui#13 契约第 4 条）：从握手请求的 Cookie 头读取 AUTH_TOKEN 校验
 * （浏览器 WS 自动携带同源/同站点 Cookie），不再接受 query 参数 token。
 * 用资源服务器同款 JwtDecoder 解码（含黑名单校验），sub 即用户 id 字符串，
 * 解析出的 Long 型 userId 放入 session attributes；解码失败返回 false 拒绝握手。
 * allowedOrigins("*")：认证 Cookie 为 SameSite=Strict，跨站握手不携带 Cookie，天然防 CSWSH。
 *
 * @author Yoruhaki
 */
@Slf4j
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    /**
     * session attributes 中用户 id 的键
     */
    public static final String ATTR_USER_ID = "userId";

    private final NotifyWebSocketHandler notifyWebSocketHandler;
    private final JwtDecoder jwtDecoder;
    private final AuthCookieService authCookieService;

    @Override
    public void registerWebSocketHandlers(@NonNull WebSocketHandlerRegistry registry) {
        registry.addHandler(notifyWebSocketHandler, "/ws")
                .addInterceptors(new JwtHandshakeInterceptor(jwtDecoder, authCookieService.getCookieName()))
                .setAllowedOrigins("*");
    }

    /**
     * JWT 握手拦截器：从握手 Cookie 头读取 AUTH_TOKEN 解码，userId 写入 attributes，
     * 任何失败（缺 Cookie / 过期 / 黑名单 / sub 非数字）都拒绝握手。
     */
    @Slf4j
    @RequiredArgsConstructor
    static class JwtHandshakeInterceptor implements HandshakeInterceptor {

        private final JwtDecoder jwtDecoder;
        private final String authCookieName;

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            String token = tokenOf(request);
            if (token == null || token.isBlank()) {
                log.debug("WebSocket 握手拒绝：缺少认证 Cookie");
                return false;
            }
            try {
                Jwt jwt = jwtDecoder.decode(token);
                // 与 JwtSubjects 约定一致：签发时 sub = 用户 id 字符串
                attributes.put(ATTR_USER_ID, Long.valueOf(jwt.getSubject()));
                return true;
            } catch (Exception e) {
                log.debug("WebSocket 握手拒绝：token 校验失败（{}）", e.getMessage());
                return false;
            }
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
            // 无需后置处理
        }

        /**
         * 从握手请求的 Cookie 头解析 AUTH_TOKEN（手动解析，避免依赖 ServletRequestServerHttpRequest 具体实现）
         */
        private String tokenOf(ServerHttpRequest request) {
            List<String> cookieHeaders = request.getHeaders().get(HttpHeaders.COOKIE);
            if (cookieHeaders == null) {
                return null;
            }
            for (String cookieHeader : cookieHeaders) {
                for (String pair : cookieHeader.split(";")) {
                    String[] kv = pair.trim().split("=", 2);
                    if (kv.length == 2 && authCookieName.equals(kv[0]) && !kv[1].isBlank()) {
                        return kv[1];
                    }
                }
            }
            return null;
        }
    }
}
