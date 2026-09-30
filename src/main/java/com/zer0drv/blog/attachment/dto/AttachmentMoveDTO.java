package com.zer0drv.blog.attachment.dto;

import lombok.Data;

/**
 * 附件移入分组入参（groupId 可空=移出分组）
 *
 * @author Yoruhaki
 */
@Data
public class AttachmentMoveDTO {

    /**
     * 目标分组id（null=移出分组）
     */
    private Long groupId;
}
