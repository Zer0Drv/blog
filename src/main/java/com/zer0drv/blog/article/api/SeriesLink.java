package com.zer0drv.blog.article.api;

import java.time.LocalDateTime;

public record SeriesLink(long id, String slug, LocalDateTime updateTime) { }
