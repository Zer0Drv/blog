package com.zer0drv.blog.admin.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 站点最新动态（/admin/stats/recent）：三组最新 5 条。
 *
 * @author Yoruhaki
 */
@Data
public class StatsRecentVO {

    private List<LatestUser> latestUsers;

    private List<LatestArticle> latestArticles;

    private List<LatestComment> latestComments;

    /**
     * 最新注册用户
     */
    @Data
    public static class LatestUser {

        private Long id;

        private String username;

        private String nickname;

        private LocalDateTime createTime;
    }

    /**
     * 最新发布文章
     */
    @Data
    public static class LatestArticle {

        private Long id;

        private String title;

        private String authorNickname;

        private LocalDateTime publishTime;
    }

    /**
     * 最新评论（content 截断前 30 字）
     */
    @Data
    public static class LatestComment {

        private Long id;

        private String content;

        private String username;

        private String articleTitle;

        private LocalDateTime createTime;
    }
}
