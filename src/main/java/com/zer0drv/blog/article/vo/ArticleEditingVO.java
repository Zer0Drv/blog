package com.zer0drv.blog.article.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

/** Author-only workspace. Never used by public detail, feeds or metadata. */
@Data
@EqualsAndHashCode(callSuper = true)
public class ArticleEditingVO extends ArticleDetailVO {
    private Long editRevision;
    private LocalDateTime workingCopyUpdatedAt;
    private String activeStatus;
    private String activeTitle;
    private String activeVisibility;
    private LocalDateTime activePublishTime;
    private boolean passwordConfigured;
}
