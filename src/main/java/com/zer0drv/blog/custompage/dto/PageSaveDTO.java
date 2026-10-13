package com.zer0drv.blog.custompage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PageSaveDTO {
    @NotBlank @Size(max = 200)
    @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*", message = "slug 仅允许小写字母、数字和连字符")
    private String slug;
    @NotBlank @Size(max = 200)
    private String title;
    @NotBlank @Size(max = 2000000)
    private String content;
    @NotBlank @Pattern(regexp = "MARKDOWN|RICHTEXT")
    private String editorType;
    @NotBlank @Pattern(regexp = "DRAFT|PUBLISHED|OFFLINE")
    private String status;
}
