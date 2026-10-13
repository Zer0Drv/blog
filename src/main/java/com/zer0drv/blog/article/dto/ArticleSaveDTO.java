package com.zer0drv.blog.article.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
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
     * 固定链接 slug（issue #14，可空）：仅小写字母/数字/连字符；
     * 新建留空由后端按标题自动生成；编辑留空表示保持现状
     */
    @Size(max = 200, message = "slug 不能超过 200 个字符")
    private String slug;

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

    /**
     * 可见性（issue #15，可空）：PUBLIC / PASSWORD / PRIVATE；新建缺省 PUBLIC，
     * 编辑留空表示保持现状。PASSWORD 时 accessPassword 必填（编辑且已有口令时留空 = 保持不变）
     */
    private String visibility;

    /**
     * 访问口令明文（可空；仅 visibility=PASSWORD 有意义）：服务端只落 BCrypt 哈希，
     * 口令策略独立于账号密码（4~64 位，供分享的弱口令由作者自担，后端 BCrypt + 限流兜底）
     */
    @Size(max = 64, message = "访问口令不能超过 64 个字符")
    private String accessPassword;

    /**
     * 定时发布时间（P0，可空）：仅 status=PUBLISHED 时生效，须晚于当前时间至少 60 秒；
     * 文章已上线（publish_time ≤ now）时该字段忽略，不可改档期
     */
    private LocalDateTime publishTime;
}