package com.zer0drv.blog.custompage.vo;

import java.time.LocalDateTime;

public record PageVO(Long id, String slug, String title, String content, String editorType,
                     String status, LocalDateTime createTime, LocalDateTime updateTime) {}
