package com.zer0drv.blog.article.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 文章新增/编辑入参（新建允许直接发布：status = DRAFT | PUBLISHED）
 *
 * @author Yoruhaki
 */
@Data
public class ArticleSaveDTO {

    /**
     * 标题
     */
    @NotBlank(message = "标题不能为空")
    @Size(max = 200, message = "标题不能超过 200 个字符")
    private String title;

    /**
     * 正文（Markdown 或富文本 HTML）
     */
    @NotBlank(message = "正文不能为空")
    private String content;

    /**
     * 编辑器类型：MARKDOWN / RICHTEXT
     */
    @NotBlank(message = "编辑器类型不能为空")
    private String editorType;

    /**
     * 摘要（缺省取 content 纯文本前 200 字）
     */
    @Size(max = 500, message = "摘要不能超过 500 个字符")
    private String summary;

    /**
     * 封面图 URL
     */
    private String cover;

    /**
     * 分类id
     */
    private Long categoryId;

    /**
     * 标签id 列表
     */
    private List<Long> tagIds;

    /**
     * 状态：DRAFT / PUBLISHED（缺省 DRAFT）
     */
    private String status;
}