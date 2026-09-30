package com.zer0drv.blog.article.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 自动保存入参（P0，仅编辑已有文章）：除正文外均可空，服务端存 Redis 草稿。
 *
 * @author Yoruhaki
 */
@Data
public class AutosaveDTO {

    /**
     * 标题（可空）
     */
    @Size(max = 200, message = "标题不能超过 200 个字符")
    private String title;

    /**
     * 正文（Markdown 或富文本 HTML）
     */
    @NotBlank(message = "正文不能为空")
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
}
