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
import com.zer0drv.blog.social.service.NotificationIntent;
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
    @org.springframework.beans.factory.annotation.Autowired
    private com.zer0drv.blog.comment.api.CommentNotificationState commentState;
    @org.springframework.beans.factory.annotation.Autowired
    private com.zer0drv.blog.article.api.ArticleCatalog articleCatalog;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

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
    public long countAllUnread() {
        return count(Wrappers.lambdaQuery(Notification.class)
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
    public void notify(NotificationIntent intent) {
        // 自己给自己不发
        if (Objects.nonNull(intent.actorId()) && intent.recipientId().equals(intent.actorId())) {
            return;
        }
        Notification notification = new Notification();
        notification.setUserId(intent.recipientId());
        notification.setType(intent.type().name());
        notification.setActorId(intent.actorId());
        notification.setArticleId(intent.articleId());
        notification.setCommentId(intent.commentId());
        notification.setSummary(intent.commentId() == null ? intent.summary() : "");
        notification.setReadFlag(UNREAD);
        // 落库通道（含 dedupe 查重）：失败仅记日志，不回滚主业务；无落库实体则不再推送/邮件
        try {
            if (intent.dedupe() && alreadyNotified(intent)) {
                // 已存在同 actor/article/type 的未删通知：取消再操作不重复发
                return;
            }
            save(notification);
        } catch (Exception e) {
            log.error("通知投递失败：type={} recipient={}", intent.type(), intent.recipientId(), e);
            return;
        }
        // WS 推送通道：实时推送通知帧给接收者（独立兜底，失败不连坐邮件）
        try {
            realtimePushService.pushToUser(intent.recipientId(), "notification",
                    assemble(List.of(notification)).getFirst());
        } catch (Exception e) {
            log.error("通知投递失败：type={} recipient={}", intent.type(), intent.recipientId(), e);
        }
        // 邮件通道：P0 §2.2 评论邮件通知（评论回复/@）+ 违规强删告知作者。
        // articleId 为 null 时（文章已物理删除）由 mail service 按类型选择文案，不拼详情链接
        if (intent.type() == NotificationType.COMMENT_REPLY || intent.type() == NotificationType.MENTION
                || intent.type() == NotificationType.ARTICLE_FORCE_DELETED) {
            try {
                String actorNickname = null;
                if (Objects.nonNull(intent.actorId())) {
                    User actor = userService.getById(intent.actorId());
                    actorNickname = Objects.nonNull(actor) ? actor.getNickname() : null;
                }
                notificationMailService.sendCommentMailAsync(intent.recipientId(), intent.type(),
                        actorNickname, intent.articleId(), intent.commentId() == null ? intent.summary() : "");
            } catch (Exception e) {
                log.error("通知投递失败：type={} recipient={}", intent.type(), intent.recipientId(), e);
            }
        }
    }

    /**
     * dedupe 查重：是否已存在同 接收人+触发人+类型+文章+评论 组合的未删通知
     */
    private boolean alreadyNotified(NotificationIntent intent) {
        LambdaQueryWrapper<Notification> exists = Wrappers.lambdaQuery(Notification.class)
                .eq(Notification::getUserId, intent.recipientId())
                .eq(Notification::getActorId, intent.actorId())
                .eq(Notification::getType, intent.type().name())
                .eq(Objects.nonNull(intent.articleId()), Notification::getArticleId, intent.articleId())
                .isNull(Objects.isNull(intent.articleId()), Notification::getArticleId)
                .eq(Objects.nonNull(intent.commentId()), Notification::getCommentId, intent.commentId())
                .isNull(Objects.isNull(intent.commentId()), Notification::getCommentId);
        return count(exists) > 0;
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
            String state = "AVAILABLE";
            if (notification.getArticleId() != null && !articleCatalog.isVisible(notification.getArticleId())) {
                state = "UNAVAILABLE";
            } else if (notification.getCommentId() != null) {
                state = commentState.state(notification.getCommentId());
            }
            vo.setContentState(state);
            if (!"AVAILABLE".equals(state)) {
                vo.setSummary(switch (state) {
                    case "DELETED" -> "该评论已删除";
                    case "ADMIN_REMOVED" -> "该评论已被管理员移除";
                    default -> "相关内容暂不可访问";
                });
                vo.setArticleId(null);
                vo.setCommentId(null);
            } else if (notification.getCommentId() != null) {
                vo.setSummary("收到评论互动，请前往文章查看");
            }
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
    @Override
    @org.springframework.transaction.annotation.Transactional
    public void retract(Long id, Long operator) {
        // Atomic transition makes repeated requests harmless and audits only the first removal.
        if (jdbc.update("UPDATE notification SET deleted=1, summary='' WHERE id=? AND deleted=0", id) == 1) {
            jdbc.update("INSERT INTO notification_retraction_audit(notification_id,operator_id) VALUES(?,?)", id, operator);
        }
    }

    @Override
    public List<NotificationVO> forComment(Long commentId) {
        return assemble(list(Wrappers.lambdaQuery(Notification.class)
                .eq(Notification::getCommentId, commentId).orderByDesc(Notification::getId).last("LIMIT 100")));
    }
}
