package com.zer0drv.blog.admin.vo;

import lombok.Data;

/**
 * 站点数据总览（/admin/stats/overview）。
 * todayXxx 以当天 00:00 起算。
 *
 * @author Yoruhaki
 */
@Data
public class StatsOverviewVO {

    /**
     * 用户总数
     */
    private Long userCount;

    /**
     * 已发布文章数
     */
    private Long articleCount;

    /**
     * 正常状态评论数（NORMAL）
     */
    private Long commentCount;

    /**
     * 全站文章总浏览量（SUM view_count，空表为 0）
     */
    private Long totalViews;

    /**
     * 文章点赞总数
     */
    private Long likeCount;

    /**
     * 文章收藏总数
     */
    private Long favoriteCount;

    /**
     * 关注关系总数
     */
    private Long followCount;

    /**
     * 今日新增用户数
     */
    private Long todayNewUsers;

    /**
     * 今日新增文章数（今日创建的 PUBLISHED 文章）
     */
    private Long todayNewArticles;

    /**
     * 今日新增评论数
     */
    private Long todayNewComments;

    /**
     * 全站未读通知总数
     */
    private Long unreadNotificationCount;
}
