package com.zer0drv.blog.custompage.dto;

import com.zer0drv.blog.common.util.SlugSyntax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PageSaveDTO {
    @NotBlank @Size(max = SlugSyntax.MAX_LENGTH)
    @Pattern(regexp = SlugSyntax.REGEX, message = "slug 仅允许小写字母、数字和连字符")
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
