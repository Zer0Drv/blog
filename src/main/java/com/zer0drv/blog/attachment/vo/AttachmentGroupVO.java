package com.zer0drv.blog.attachment.vo;

import lombok.Data;

/**
 * 附件分组 VO（count=组内附件数）
 *
 * @author Yoruhaki
 */
@Data
public class AttachmentGroupVO {

    /**
     * 分组id
     */
    private Long id;

    /**
     * 分组名
     */
    private String name;

    /**
     * 排序值
     */
    private Integer sort;

    /**
     * 组内附件数
     */
    private Long count;
}
