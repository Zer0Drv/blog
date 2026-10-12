package com.zer0drv.blog.admin.service.impl;

import com.zer0drv.blog.admin.service.AdminStatsService;
import com.zer0drv.blog.admin.vo.StatsOverviewVO;
import com.zer0drv.blog.admin.vo.StatsRecentVO;
import com.zer0drv.blog.article.api.ArticleCatalog;
import com.zer0drv.blog.article.api.ArticlePublishedEntry;
import com.zer0drv.blog.article.api.ArticleRef;
import com.zer0drv.blog.article.api.ArticleStatsPort;
import com.zer0drv.blog.comment.api.CommentStatsEntry;
import com.zer0drv.blog.comment.api.CommentStatsPort;
import com.zer0drv.blog.interaction.service.InteractionService;
import com.zer0drv.blog.social.service.FollowService;
import com.zer0drv.blog.social.service.NotificationService;
import com.zer0drv.blog.user.service.UserService;
import com.zer0drv.blog.user.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 管理端全站统计（issue #34 第五步）：所有统计查询经拥有数据的模块的
 * 公共 API（article.api / comment.api 端口与各模块公共 Service 窄方法）完成，
 * 不再直注任何业务模块 Mapper；统计 SQL 与各口径（含「仅 status=PUBLISHED、
 * 无 publish_time 条件」的管理端语义）逐字节等价于收口前的内联实现。
 *
 * @author Yoruhaki
 */
@Service
@RequiredArgsConstructor
public class AdminStatsServiceImpl implements AdminStatsService {

    /**
     * 最新评论内容摘要长度
     */
    private static final int COMMENT_SUMMARY_MAX_LENGTH = 30;

    /**
     * 各组最新条数
     */
    private static final int RECENT_SIZE = 5;

    private final UserService userService;
    private final ArticleStatsPort articleStatsPort;
    private final ArticleCatalog articleCatalog;
    private final CommentStatsPort commentStatsPort;
    private final InteractionService interactionService;
    private final FollowService followService;
    private final NotificationService notificationService;

    @Override
    public StatsOverviewVO overview() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        StatsOverviewVO vo = new StatsOverviewVO();
        vo.setUserCount(userService.countAllUsers());
        vo.setArticleCount(articleStatsPort.countPublished());
        vo.setCommentCount(commentStatsPort.countNormal());
        vo.setTotalViews(articleStatsPort.totalViews());
        vo.setLikeCount(interactionService.countArticleLikes());
        vo.setFavoriteCount(interactionService.countArticleFavorites());
        vo.setFollowCount(followService.countAllFollows());
        vo.setTodayNewUsers(userService.countUsersCreatedSince(todayStart));
        vo.setTodayNewArticles(articleStatsPort.countPublishedSince(todayStart));
        vo.setTodayNewComments(commentStatsPort.countCreatedSince(todayStart));
        vo.setUnreadNotificationCount(notificationService.countAllUnread());
        return vo;
    }

    @Override
    public StatsRecentVO recent() {
        StatsRecentVO vo = new StatsRecentVO();
        vo.setLatestUsers(latestUsers());
        vo.setLatestArticles(latestArticles());
        vo.setLatestComments(latestComments());
        return vo;
    }

    private List<StatsRecentVO.LatestUser> latestUsers() {
        return userService.listLatestUsers(RECENT_SIZE)
                .stream().map(user -> {
                    StatsRecentVO.LatestUser vo = new StatsRecentVO.LatestUser();
                    vo.setId(user.getId());
                    vo.setUsername(user.getUsername());
                    vo.setNickname(user.getNickname());
                    vo.setCreateTime(user.getCreateTime());
                    return vo;
                }).toList();
    }

    private List<StatsRecentVO.LatestArticle> latestArticles() {
        List<ArticlePublishedEntry> articles = articleStatsPort.listPublishedLatest(RECENT_SIZE);
        if (articles.isEmpty()) {
            return List.of();
        }
        Set<Long> authorIds = articles.stream().map(ArticlePublishedEntry::authorId).collect(Collectors.toSet());
        Map<Long, UserVO> authorMap = userService.mapUsersByIds(authorIds);
        return articles.stream().map(article -> {
            StatsRecentVO.LatestArticle vo = new StatsRecentVO.LatestArticle();
            vo.setId(article.id());
            vo.setTitle(article.title());
            UserVO author = authorMap.get(article.authorId());
            vo.setAuthorNickname(Objects.nonNull(author) ? author.getNickname() : null);
            vo.setPublishTime(article.publishTime());
            return vo;
        }).toList();
    }

    private List<StatsRecentVO.LatestComment> latestComments() {
        List<CommentStatsEntry> comments = commentStatsPort.listLatest(RECENT_SIZE);
        if (comments.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = comments.stream().map(CommentStatsEntry::userId).collect(Collectors.toSet());
        Map<Long, UserVO> userMap = userService.mapUsersByIds(userIds);
        Set<Long> articleIds = comments.stream().map(CommentStatsEntry::articleId).collect(Collectors.toSet());
        Map<Long, String> articleTitleMap = articleCatalog.listRefsByIds(articleIds).stream()
                .collect(Collectors.toMap(ArticleRef::id, ArticleRef::title));
        return comments.stream().map(comment -> {
            StatsRecentVO.LatestComment vo = new StatsRecentVO.LatestComment();
            vo.setId(comment.id());
            String content = comment.content();
            vo.setContent(content.length() <= COMMENT_SUMMARY_MAX_LENGTH
                    ? content : content.substring(0, COMMENT_SUMMARY_MAX_LENGTH));
            UserVO user = userMap.get(comment.userId());
            vo.setUsername(Objects.nonNull(user) ? user.getUsername() : null);
            vo.setArticleTitle(articleTitleMap.get(comment.articleId()));
            vo.setCreateTime(comment.createTime());
            return vo;
        }).toList();
    }
}
