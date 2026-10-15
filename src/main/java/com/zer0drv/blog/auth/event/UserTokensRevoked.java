package com.zer0drv.blog.auth.event;

/** 用户级吊销完成后的同步事件，包括活跃 token 集合为空的情况。 */
public record UserTokensRevoked(Long userId) {
}
