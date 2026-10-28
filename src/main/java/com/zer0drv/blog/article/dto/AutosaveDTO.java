package com.zer0drv.blog.article.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 自动保存完整编辑状态：标题与正文允许空，服务端持久化独立工作稿。
 *
 * @author Yoruhaki
 */
@Data
public class AutosaveDTO {

    @NotNull @PositiveOrZero
    private Long editRevision;
    @Size(max = 200)
    private String slug;
    private String editorType;
    private List<Long> tagIds;
    private LocalDateTime publishTime;
    /** Transient request value: immediately hashed, never written to Redis or returned. */
    @Size(max = 64)
    private String accessPassword;

    /**
     * 标题（可空）
     */
    @Size(max = 200, message = "标题不能超过 200 个字符")
    private String title;

    /**
     * 正文（Markdown 或富文本 HTML）
     */
    private String content;

    /**
     * 摘要（可空）
     */
    @Size(max = 500, message = "摘要不能超过 500 个字符")
    private String summary;

    /**
     * 封面图 URL（可空）
     */
    @Size(max = 512, message = "封面图 URL 不能超过 512 个字符")
    private String cover;

    /**
     * 分类id（可空）
     */
    private Long categoryId;

    /** 可见性选择随工作稿恢复；口令只持久化哈希。 */
    private String visibility;
}
