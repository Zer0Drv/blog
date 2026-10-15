package com.zer0drv.blog.auth.event;

/** 黑名单写入后的同步事件；只携带凭据标识，不携带原始 token。 */
public record TokenRevoked(String jti) {
}
