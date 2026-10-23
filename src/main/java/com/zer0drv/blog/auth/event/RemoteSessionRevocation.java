package com.zer0drv.blog.auth.event;

/** Remote delivery only: never mutate account versions or broadcast this event again. */
public record RemoteSessionRevocation(Long userId, String jti) {}
