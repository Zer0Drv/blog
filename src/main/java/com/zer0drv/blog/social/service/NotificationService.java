package com.zer0drv.blog.social.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.social.domain.Notification;
import com.zer0drv.blog.social.enums.NotificationType;
import com.zer0drv.blog.social.vo.NotificationVO;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 统一通知中心
 *
 * @author Yoruhaki
 */
public interface NotificationService extends IService<Notification> {

    /**
     * 我的通知分页（create_time 倒序；type 可空按类型过滤）
     */
    PageResult<NotificationVO> pageMine(long page, long size, String type, Jwt jwt);

    /**
     * 我的未读通知数
     */
    long unreadCount(Jwt jwt);

    /**
     * 标记单条已读（仅本人，否则 40301；不存在的 id 静默成功）
     */
    void markRead(Long id, Jwt jwt);

    /**
     * 全部标记已读
     */
    void markAllRead(Jwt jwt);

    /**
     * 安全发送通知：内部 try/catch 兜底（失败仅 log.warn，不回滚主业务）；
     * 接收人 == 触发人时不发；dedupe=true 时按 接收人+触发人+类型+文章 防重
     * （已存在同组合的未删通知则跳过，用于 ARTICLE_LIKE / FOLLOW 取消后再操作不重复打扰）。
     */
    void notify(Long userId, NotificationType type, Long actorId,
                Long articleId, Long commentId, String summary, boolean dedupe);
}
