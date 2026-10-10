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
     * 把某用户的、来自某触发人的、某类型的全部未读通知标记已读
     * （幂等：无可更新行时静默成功；用于读完私信会话后同步 PRIVATE_MESSAGE 通知已读）
     */
    void markReadByTypeAndActor(Long userId, NotificationType type, Long actorId);

    /**
     * 投递一条通知意图：接收人 == 触发人时不发；intent.dedupe() 时按 接收人+触发人+类型+文章+评论 防重
     * （已存在同组合的未删通知则跳过，用于 ARTICLE_LIKE / COMMENT_LIKE / FOLLOW 取消后再操作不重复打扰）。
     * 落库、WS 推送、邮件三通道各自隔离兜底：任一通道失败仅 log.error 记录，不回滚主业务、不连坐其他通道。
     */
    void notify(NotificationIntent intent);
}
