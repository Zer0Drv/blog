package com.zer0drv.blog.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.common.util.JwtSubjects;
import com.zer0drv.blog.social.domain.Notification;
import com.zer0drv.blog.social.enums.NotificationType;
import com.zer0drv.blog.social.mapper.NotificationMapper;
import com.zer0drv.blog.social.service.NotificationMailService;
import com.zer0drv.blog.social.service.NotificationService;
import com.zer0drv.blog.social.service.RealtimePushService;
import com.zer0drv.blog.social.vo.NotificationVO;
import com.zer0drv.blog.social.vo.SocialUserVO;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

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
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl extends ServiceImpl<NotificationMapper, Notification>
        implements NotificationService {

    private static final short UNREAD = 0;
    private static final short READ = 1;

    private final UserService userService;
    private final RealtimePushService realtimePushService;
    private final NotificationMailService notificationMailService;

    @Override
    public PageResult<NotificationVO> pageMine(long page, long size, String type, Jwt jwt) {
        Long userId = JwtSubjects.userIdOf(jwt);
        LambdaQueryWrapper<Notification> wrapper = Wrappers.lambdaQuery(Notification.class)
                .eq(Notification::getUserId, userId)
                .orderByDesc(Notification::getCreateTime)
                .orderByDesc(Notification::getId);
        if (Objects.nonNull(type) && !type.isBlank()) {
            if (!NotificationType.isValid(type)) {
                throw new BusinessException(StatusCode.PARAM_INVALID);
            }
            wrapper.eq(Notification::getType, type);
        }
        Page<Notification> result = page(new Page<>(page, size), wrapper);
        return PageResult.of(assemble(result.getRecords()), result.getTotal(), page, size);
    }

    @Override
    public long unreadCount(Jwt jwt) {
        Long userId = JwtSubjects.userIdOf(jwt);
        return count(Wrappers.lambdaQuery(Notification.class)
                .eq(Notification::getUserId, userId)
                .eq(Notification::getReadFlag, UNREAD));
    }

    @Override
    public void markRead(Long id, Jwt jwt) {
        Notification notification = getById(id);
        if (Objects.isNull(notification)) {
            // 幂等：不存在的 id 静默成功
            return;
        }
        Long userId = JwtSubjects.userIdOf(jwt);
        if (!notification.getUserId().equals(userId)) {
            throw new BusinessException(StatusCode.NOT_AUTHOR);
        }
        if (notification.getReadFlag() == READ) {
            return;
        }
        notification.setReadFlag(READ);
        updateById(notification);
    }

    @Override
    public void markAllRead(Jwt jwt) {
        Long userId = JwtSubjects.userIdOf(jwt);
        update(Wrappers.lambdaUpdate(Notification.class)
                .set(Notification::getReadFlag, READ)
                .eq(Notification::getUserId, userId)
                .eq(Notification::getReadFlag, UNREAD));
    }

    @Override
    public void markReadByTypeAndActor(Long userId, NotificationType type, Long actorId) {
        update(Wrappers.lambdaUpdate(Notification.class)
                .set(Notification::getReadFlag, READ)
                .eq(Notification::getUserId, userId)
                .eq(Notification::getType, type.name())
                .eq(Notification::getActorId, actorId)
                .eq(Notification::getReadFlag, UNREAD));
    }

    @Override
    public void notify(Long userId, NotificationType type, Long actorId,
                       Long articleId, Long commentId, String summary, boolean dedupe) {
        try {
            if (Objects.isNull(userId)) {
                return;
            }
            // 自己给自己不发
            if (Objects.nonNull(actorId) && userId.equals(actorId)) {
                return;
            }
            if (dedupe) {
                LambdaQueryWrapper<Notification> exists = Wrappers.lambdaQuery(Notification.class)
                        .eq(Notification::getUserId, userId)
                        .eq(Notification::getActorId, actorId)
                        .eq(Notification::getType, type.name())
                        .eq(Objects.nonNull(articleId), Notification::getArticleId, articleId)
                        .isNull(Objects.isNull(articleId), Notification::getArticleId)
                        .eq(Objects.nonNull(commentId), Notification::getCommentId, commentId)
                        .isNull(Objects.isNull(commentId), Notification::getCommentId);
                if (count(exists) > 0) {
                    // 已存在同 actor/article/type 的未删通知：取消再操作不重复发
                    return;
                }
            }
            Notification notification = new Notification();
            notification.setUserId(userId);
            notification.setType(type.name());
            notification.setActorId(actorId);
            notification.setArticleId(articleId);
            notification.setCommentId(commentId);
            notification.setSummary(Objects.isNull(summary) ? "" : summary);
            notification.setReadFlag(UNREAD);
            save(notification);
            // 实时推送通知帧给接收者（在 notify 的 try/catch 兜底内，失败不影响主业务）
            realtimePushService.pushToUser(userId, "notification",
                    assemble(List.of(notification)).getFirst());
            // P0 §2.2 评论邮件通知：仅评论回复/@ 两类触发（@Async 异步发送，全部失败自兜底）
            if (type == NotificationType.COMMENT_REPLY || type == NotificationType.MENTION) {
                String actorNickname = null;
                if (Objects.nonNull(actorId)) {
                    User actor = userService.getById(actorId);
                    actorNickname = Objects.nonNull(actor) ? actor.getNickname() : null;
                }
                notificationMailService.sendCommentMailAsync(userId, type, actorNickname, articleId, summary);
            }
        } catch (Exception e) {
            // 通知创建失败不回滚主业务
            log.warn("通知创建失败：type={}, userId={}, actorId={}, articleId={}, reason={}",
                    type, userId, actorId, articleId, e.getMessage());
        }
    }

    /**
     * 批量组装通知 VO：actor 信息一次批量查用户组装，避免 N+1
     */
    private List<NotificationVO> assemble(List<Notification> notifications) {
        if (notifications.isEmpty()) {
            return List.of();
        }
        Set<Long> actorIds = notifications.stream().map(Notification::getActorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, User> actorMap = actorIds.isEmpty() ? Map.of()
                : userService.listByIds(actorIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return notifications.stream().map(notification -> {
            NotificationVO vo = new NotificationVO();
            vo.setId(notification.getId());
            vo.setType(notification.getType());
            vo.setSummary(notification.getSummary());
            vo.setArticleId(notification.getArticleId());
            vo.setCommentId(notification.getCommentId());
            vo.setReadFlag(notification.getReadFlag());
            // create_time 走数据库默认值：落库后实时推送时实体尚未回查为 null，兜底取当前时间
            vo.setCreateTime(Objects.nonNull(notification.getCreateTime())
                    ? notification.getCreateTime() : LocalDateTime.now());
            User actor = Objects.nonNull(notification.getActorId())
                    ? actorMap.get(notification.getActorId()) : null;
            if (Objects.nonNull(actor)) {
                SocialUserVO actorVO = new SocialUserVO();
                actorVO.setId(actor.getId());
                actorVO.setUsername(actor.getUsername());
                actorVO.setNickname(actor.getNickname());
                actorVO.setAvatar(actor.getAvatar());
                vo.setActor(actorVO);
            }
            return vo;
        }).collect(Collectors.toList());
    }
}
