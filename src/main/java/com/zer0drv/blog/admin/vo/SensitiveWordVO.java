package com.zer0drv.blog.admin.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author Yoruhaki
 */
@Data
public class SensitiveWordVO {

    private Long id;

    private String word;

    private LocalDateTime createTime;
}
