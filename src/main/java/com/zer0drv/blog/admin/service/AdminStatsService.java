package com.zer0drv.blog.admin.service;

import com.zer0drv.blog.admin.vo.StatsOverviewVO;
import com.zer0drv.blog.admin.vo.StatsRecentVO;

/**
 * 站点数据 dashboard（M5，仅 ADMIN，由 SecurityConfig /admin/** 保护）。
 *
 * @author Yoruhaki
 */
public interface AdminStatsService {

    /**
     * 站点数据总览：各维度 count/sum + 今日新增（当天 00:00 起算）+ 全站未读通知数
     */
    StatsOverviewVO overview();

    /**
     * 最新动态：最新 5 个用户 / 5 篇发布文章 / 5 条评论
     */
    StatsRecentVO recent();
}
