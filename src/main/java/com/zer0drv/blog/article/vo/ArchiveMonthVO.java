package com.zer0drv.blog.article.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 归档（按月分组）：月份倒序，月内文章按发布时间倒序
 *
 * @author Yoruhaki
 */
@Data
public class ArchiveMonthVO {

    /**
     * 月份，格式 yyyy-MM
     */
    private String month;

    /**
     * 当月文章数
     */
    private Integer count;

    /**
     * 当月文章（publishTime 倒序）
     */
    private List<Item> articles;

    /**
     * 归档文章条目
     */
    @Data
    public static class Item {

        private Long id;

        private String title;

        /**
         * 固定链接 slug（issue #14）：前端归档列表优先按 slug 生成链接，缺失回退 id
         */
        private String slug;

        private LocalDateTime publishTime;
    }
}
