package com.zer0drv.blog.custompage.api;

import java.time.LocalDateTime;

/** 导航与 sitemap 的公开投影，不包含正文。 */
public record PageLink(Long id, String slug, String title, LocalDateTime updateTime) {}
