package com.zer0drv.blog.custompage.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/** 独立页面实体；文章生命周期与计数不受页面发布影响。 */
@Data
@TableName("custom_page")
public class CustomPage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String slug;
    private String title;
    private String content;
    private String editorType;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableLogic
    private Short deleted;
}
