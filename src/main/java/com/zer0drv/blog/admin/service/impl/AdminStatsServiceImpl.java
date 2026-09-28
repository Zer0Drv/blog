package com.zer0drv.blog.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zer0drv.blog.admin.service.AdminStatsService;
import com.zer0drv.blog.admin.vo.StatsOverviewVO;
import com.zer0drv.blog.admin.vo.StatsRecentVO;
import com.zer0drv.blog.article.domain.Article;
import com.zer0drv.blog.article.enums.ArticleStatus;
import com.zer0drv.blog.article.mapper.ArticleMapper;
import com.zer0drv.blog.comment.domain.Comment;
import com.zer0drv.blog.comment.enums.CommentStatus;
import com.zer0drv.blog.comment.mapper.CommentMapper;
import com.zer0drv.blog.interaction.domain.ArticleFavorite;
import com.zer0drv.blog.interaction.domain.ArticleLike;
import com.zer0drv.blog.interaction.mapper.ArticleFavoriteMapper;
import com.zer0drv.blog.interaction.mapper.ArticleLikeMapper;
import com.zer0drv.blog.social.domain.Follow;
import com.zer0drv.blog.social.domain.Notification;
import com.zer0drv.blog.social.mapper.FollowMapper;
import com.zer0drv.blog.social.mapper.NotificationMapper;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
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

    private static final short UNREAD = 0;

    private final UserMapper userMapper;
    private final ArticleMapper articleMapper;
    private final CommentMapper commentMapper;
    private final ArticleLikeMapper articleLikeMapper;
    private final ArticleFavoriteMapper articleFavoriteMapper;
    private final FollowMapper followMapper;
    private final NotificationMapper notificationMapper;

    @Override
    public StatsOverviewVO overview() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        StatsOverviewVO vo = new StatsOverviewVO();
        vo.setUserCount(userMapper.selectCount(Wrappers.lambdaQuery(User.class)));
        vo.setArticleCount(articleMapper.selectCount(Wrappers.lambdaQuery(Article.class)
                .eq(Article::getStatus, ArticleStatus.PUBLISHED.name())));
        vo.setCommentCount(commentMapper.selectCount(Wrappers.lambdaQuery(Comment.class)
                .eq(Comment::getStatus, CommentStatus.NORMAL.name())));
        vo.setTotalViews(totalViews());
        vo.setLikeCount(articleLikeMapper.selectCount(Wrappers.lambdaQuery(ArticleLike.class)));
        vo.setFavoriteCount(articleFavoriteMapper.selectCount(Wrappers.lambdaQuery(ArticleFavorite.class)));
        vo.setFollowCount(followMapper.selectCount(Wrappers.lambdaQuery(Follow.class)));
        vo.setTodayNewUsers(userMapper.selectCount(Wrappers.lambdaQuery(User.class)
                .ge(User::getCreateTime, todayStart)));
        vo.setTodayNewArticles(articleMapper.selectCount(Wrappers.lambdaQuery(Article.class)
                .eq(Article::getStatus, ArticleStatus.PUBLISHED.name())
                .ge(Article::getCreateTime, todayStart)));
        vo.setTodayNewComments(commentMapper.selectCount(Wrappers.lambdaQuery(Comment.class)
                .ge(Comment::getCreateTime, todayStart)));
        vo.setUnreadNotificationCount(notificationMapper.selectCount(Wrappers.lambdaQuery(Notification.class)
                .eq(Notification::getReadFlag, UNREAD)));
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

    /**
     * 全站文章总浏览量：SUM(view_count)，空表 / 全 NULL 兜底 0
     */
    private Long totalViews() {
        List<Map<String, Object>> rows = articleMapper.selectMaps(new QueryWrapper<Article>()
                .select("IFNULL(SUM(view_count), 0) AS totalViews"));
        if (rows.isEmpty()) {
            return 0L;
        }
        Object total = rows.getFirst().get("totalViews");
        return Objects.nonNull(total) ? ((Number) total).longValue() : 0L;
    }

    private List<StatsRecentVO.LatestUser> latestUsers() {
        return userMapper.selectList(Wrappers.lambdaQuery(User.class)
                        .orderByDesc(User::getId)
                        .last("LIMIT " + RECENT_SIZE))
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
        List<Article> articles = articleMapper.selectList(Wrappers.lambdaQuery(Article.class)
                .eq(Article::getStatus, ArticleStatus.PUBLISHED.name())
                .orderByDesc(Article::getPublishTime)
                .last("LIMIT " + RECENT_SIZE));
        if (articles.isEmpty()) {
            return List.of();
        }
        Set<Long> authorIds = articles.stream().map(Article::getAuthorId).collect(Collectors.toSet());
        Map<Long, User> authorMap = userMapper.selectByIds(authorIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return articles.stream().map(article -> {
            StatsRecentVO.LatestArticle vo = new StatsRecentVO.LatestArticle();
            vo.setId(article.getId());
            vo.setTitle(article.getTitle());
            User author = authorMap.get(article.getAuthorId());
            vo.setAuthorNickname(Objects.nonNull(author) ? author.getNickname() : null);
            vo.setPublishTime(article.getPublishTime());
            return vo;
        }).toList();
    }

    private List<StatsRecentVO.LatestComment> latestComments() {
        List<Comment> comments = commentMapper.selectList(Wrappers.lambdaQuery(Comment.class)
                .orderByDesc(Comment::getId)
                .last("LIMIT " + RECENT_SIZE));
        if (comments.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = comments.stream().map(Comment::getUserId).collect(Collectors.toSet());
        Map<Long, User> userMap = userMapper.selectByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Set<Long> articleIds = comments.stream().map(Comment::getArticleId).collect(Collectors.toSet());
        Map<Long, String> articleTitleMap = articleMapper.selectByIds(articleIds).stream()
                .collect(Collectors.toMap(Article::getId, Article::getTitle));
        return comments.stream().map(comment -> {
            StatsRecentVO.LatestComment vo = new StatsRecentVO.LatestComment();
            vo.setId(comment.getId());
            String content = comment.getContent();
            vo.setContent(content.length() <= COMMENT_SUMMARY_MAX_LENGTH
                    ? content : content.substring(0, COMMENT_SUMMARY_MAX_LENGTH));
            User user = userMap.get(comment.getUserId());
            vo.setUsername(Objects.nonNull(user) ? user.getUsername() : null);
            vo.setArticleTitle(articleTitleMap.get(comment.getArticleId()));
            vo.setCreateTime(comment.getCreateTime());
            return vo;
        }).toList();
    }
}
