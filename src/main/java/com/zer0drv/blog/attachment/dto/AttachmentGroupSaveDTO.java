package com.zer0drv.blog.attachment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 附件分组新建/重命名入参
 *
 * @author Yoruhaki
 */
@Data
public class AttachmentGroupSaveDTO {

    /**
     * 分组名
     */
    @NotBlank(message = "分组名不能为空")
    @Size(max = 64, message = "分组名不能超过 64 个字符")
    private String name;
}
