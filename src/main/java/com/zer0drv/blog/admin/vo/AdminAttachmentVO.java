package com.zer0drv.blog.admin.vo;

import com.zer0drv.blog.attachment.vo.AttachmentVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 附件 VO（管理端，追加上传者 userId）
 *
 * @author Yoruhaki
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class AdminAttachmentVO extends AttachmentVO {

    /**
     * 上传者id
     */
    private Long userId;
}
