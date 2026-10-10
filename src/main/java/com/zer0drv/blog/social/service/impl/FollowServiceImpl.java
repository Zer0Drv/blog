package com.zer0drv.blog.social.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zer0drv.blog.article.domain.Article;
import com.zer0drv.blog.article.domain.ArticleVisibility;
import com.zer0drv.blog.article.service.ArticleService;
import com.zer0drv.blog.article.vo.ArticleListVO;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.common.util.JwtSubjects;
import com.zer0drv.blog.social.domain.Follow;
import com.zer0drv.blog.social.enums.NotificationType;
import com.zer0drv.blog.social.mapper.FollowMapper;
import com.zer0drv.blog.social.service.FollowService;
import com.zer0drv.blog.social.service.NotificationService;
import com.zer0drv.blog.social.vo.FollowUserVO;
import com.zer0drv.blog.social.vo.UserProfileVO;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

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
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements FollowService {

    private final UserService userService;
    private final ArticleService articleService;
    private final NotificationService notificationService;

    @Override
    public void follow(Long id, Jwt jwt) {
        Long userId = JwtSubjects.userIdOf(jwt);
        if (userId.equals(id)) {
            throw new BusinessException(StatusCode.FOLLOW_SELF_INVALID);
        }
        User followee = userService.getById(id);
        if (Objects.isNull(followee)) {
            throw new BusinessException(StatusCode.USER_NOT_EXIST);
        }
        boolean inserted = false;
        try {
            Follow follow = new Follow();
            follow.setFollowerId(userId);
            follow.setFolloweeId(id);
            save(follow);
            inserted = true;
        } catch (DuplicateKeyException _) {
            // 幂等：已关注或并发重复关注，唯一索引兜底，静默成功（不再重复发通知）
        }
        if (inserted) {
            // 通知被关注者（防重：已存在同 actor/type 的未删 FOLLOW 通知则跳过；失败不影响主业务）
            notificationService.notify(id, NotificationType.FOLLOW, userId, null, null, "", true);
        }
    }

    @Override
    public void unfollow(Long id, Jwt jwt) {
        Long userId = JwtSubjects.userIdOf(jwt);
        // follow 表无逻辑删除字段，物理删除
        boolean removed = remove(Wrappers.lambdaQuery(Follow.class)
                .eq(Follow::getFollowerId, userId)
                .eq(Follow::getFolloweeId, id));
        if (!removed) {
            throw new BusinessException(StatusCode.USER_NOT_FOLLOWED);
        }
    }

    @Override
    public PageResult<FollowUserVO> pageFollowers(Long userId, long page, long size, Jwt jwt) {
        Page<Follow> result = page(new Page<>(page, size), Wrappers.lambdaQuery(Follow.class)
                .eq(Follow::getFolloweeId, userId)
                .orderByDesc(Follow::getId));
        List<Long> followerIds = result.getRecords().stream().map(Follow::getFollowerId).toList();
        return PageResult.of(assembleFollowUsers(followerIds, jwt), result.getTotal(), page, size);
    }

    @Override
    public PageResult<FollowUserVO> pageFollowing(Long userId, long page, long size, Jwt jwt) {
        Page<Follow> result = page(new Page<>(page, size), Wrappers.lambdaQuery(Follow.class)
                .eq(Follow::getFollowerId, userId)
                .orderByDesc(Follow::getId));
        List<Long> followeeIds = result.getRecords().stream().map(Follow::getFolloweeId).toList();
        return PageResult.of(assembleFollowUsers(followeeIds, jwt), result.getTotal(), page, size);
    }

    @Override
    public UserProfileVO profile(Long userId, Jwt jwt) {
        User user = userService.getById(userId);
        if (Objects.isNull(user)) {
            throw new BusinessException(StatusCode.USER_NOT_EXIST);
        }
        UserProfileVO vo = new UserProfileVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setBio(user.getBio());
        vo.setFollowerCount(count(Wrappers.lambdaQuery(Follow.class).eq(Follow::getFolloweeId, userId)));
        vo.setFollowingCount(count(Wrappers.lambdaQuery(Follow.class).eq(Follow::getFollowerId, userId)));
        vo.setArticleCount(articleService.count(ArticleVisibility.apply(Wrappers.lambdaQuery(Article.class)
                .eq(Article::getAuthorId, userId))));
        vo.setFollowed(Objects.nonNull(jwt) && isFollowed(JwtSubjects.userIdOf(jwt), userId));
        return vo;
    }

    @Override
    public PageResult<ArticleListVO> pageFeed(long page, long size, Jwt jwt) {
        Long userId = JwtSubjects.userIdOf(jwt);
        List<Long> followeeIds = list(Wrappers.lambdaQuery(Follow.class)
                        .eq(Follow::getFollowerId, userId))
                .stream().map(Follow::getFolloweeId).toList();
        if (followeeIds.isEmpty()) {
            // 未关注任何人返回空页
            return PageResult.of(List.of(), 0, page, size);
        }
        Page<Article> result = articleService.page(new Page<>(page, size),
                ArticleVisibility.apply(Wrappers.lambdaQuery(Article.class))
                        .in(Article::getAuthorId, followeeIds)
                        .orderByDesc(Article::getPublishTime));
        return PageResult.of(articleService.assemble(result.getRecords()), result.getTotal(), page, size);
    }

    @Override
    public PageResult<ArticleListVO> pageUserArticles(Long userId, long page, long size) {
        Page<Article> result = articleService.page(new Page<>(page, size),
                ArticleVisibility.apply(Wrappers.lambdaQuery(Article.class)
                                .eq(Article::getAuthorId, userId))
                        .orderByDesc(Article::getPublishTime));
        return PageResult.of(articleService.assemble(result.getRecords()), result.getTotal(), page, size);
    }

    /**
     * 批量组装粉丝/关注列表 VO：用户信息 + 当前登录者的关注状态，内存联查避免 N+1；
     * userIds 顺序即展示顺序（已按关注时间倒序）
     */
    private List<FollowUserVO> assembleFollowUsers(List<Long> userIds, Jwt jwt) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        Map<Long, User> userMap = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Set<Long> followedIds = followedIdsOfCurrentUser(userIds, jwt);
        return userIds.stream()
                .map(userMap::get)
                .filter(Objects::nonNull)
                .map(user -> {
                    FollowUserVO vo = new FollowUserVO();
                    vo.setId(user.getId());
                    vo.setUsername(user.getUsername());
                    vo.setNickname(user.getNickname());
                    vo.setAvatar(user.getAvatar());
                    vo.setBio(user.getBio());
                    vo.setFollowed(followedIds.contains(user.getId()));
                    return vo;
                }).collect(Collectors.toList());
    }

    /**
     * 当前登录者在给定用户集合中已关注的 id 集；匿名返回空集
     */
    private Set<Long> followedIdsOfCurrentUser(List<Long> userIds, Jwt jwt) {
        if (Objects.isNull(jwt) || userIds.isEmpty()) {
            return Set.of();
        }
        Long currentUserId = JwtSubjects.userIdOf(jwt);
        return list(Wrappers.lambdaQuery(Follow.class)
                        .eq(Follow::getFollowerId, currentUserId)
                        .in(Follow::getFolloweeId, userIds))
                .stream().map(Follow::getFolloweeId).collect(Collectors.toSet());
    }

    private boolean isFollowed(Long followerId, Long followeeId) {
        return count(Wrappers.lambdaQuery(Follow.class)
                .eq(Follow::getFollowerId, followerId)
                .eq(Follow::getFolloweeId, followeeId)) > 0;
    }
}
