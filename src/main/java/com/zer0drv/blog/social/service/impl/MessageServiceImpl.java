package com.zer0drv.blog.social.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.common.util.JwtSubjects;
import com.zer0drv.blog.social.domain.PrivateMessage;
import com.zer0drv.blog.social.dto.MessageSendDTO;
import com.zer0drv.blog.social.enums.NotificationType;
import com.zer0drv.blog.social.mapper.PrivateMessageMapper;
import com.zer0drv.blog.social.service.MessageService;
import com.zer0drv.blog.social.service.NotificationService;
import com.zer0drv.blog.social.vo.ConversationVO;
import com.zer0drv.blog.social.vo.MessageVO;
import com.zer0drv.blog.social.vo.SocialUserVO;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author Yoruhaki
 */
@Service
@RequiredArgsConstructor
public class MessageServiceImpl extends ServiceImpl<PrivateMessageMapper, PrivateMessage>
        implements MessageService {

    /**
     * 私信内容最大长度
     */
    private static final int CONTENT_MAX_LENGTH = 1000;

    /**
     * 私信通知摘要长度（内容前 50 字）
     */
    private static final int SUMMARY_MAX_LENGTH = 50;

    private static final short UNREAD = 0;
    private static final short READ = 1;

    private final UserService userService;
    private final NotificationService notificationService;

    @Override
    public List<ConversationVO> conversations(Jwt jwt) {
        Long userId = JwtSubjects.userIdOf(jwt);
        // 一次查出与我有关的全部消息（数据量小，内存按会话分组）
        List<PrivateMessage> messages = list(Wrappers.lambdaQuery(PrivateMessage.class)
                .and(w -> w.eq(PrivateMessage::getSenderId, userId)
                        .or().eq(PrivateMessage::getReceiverId, userId))
                .orderByDesc(PrivateMessage::getId));
        if (messages.isEmpty()) {
            return List.of();
        }
        // 每会话：最新一条（已按 id 倒序，首个即最新）+ 发给我的未读数
        Map<Long, PrivateMessage> lastMessageMap = new HashMap<>();
        Map<Long, Long> unreadCountMap = new HashMap<>();
        for (PrivateMessage message : messages) {
            Long peerId = peerIdOf(message, userId);
            lastMessageMap.putIfAbsent(peerId, message);
            if (userId.equals(message.getReceiverId()) && message.getReadFlag() == UNREAD) {
                unreadCountMap.merge(peerId, 1L, Long::sum);
            }
        }
        Map<Long, User> peerMap = userService.listByIds(lastMessageMap.keySet()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        List<ConversationVO> conversations = new ArrayList<>();
        for (Map.Entry<Long, PrivateMessage> entry : lastMessageMap.entrySet()) {
            Long peerId = entry.getKey();
            User peer = peerMap.get(peerId);
            if (Objects.isNull(peer)) {
                // 对方账号已注销：不展示该会话
                continue;
            }
            PrivateMessage last = entry.getValue();
            ConversationVO.LastMessage lastMessage = new ConversationVO.LastMessage();
            lastMessage.setContent(last.getContent());
            lastMessage.setCreateTime(last.getCreateTime());
            lastMessage.setSenderId(last.getSenderId());
            ConversationVO vo = new ConversationVO();
            vo.setPeer(toSocialUserVO(peer));
            vo.setLastMessage(lastMessage);
            vo.setUnreadCount(unreadCountMap.getOrDefault(peerId, 0L));
            conversations.add(vo);
        }
        conversations.sort(Comparator.comparing(
                vo -> vo.getLastMessage().getCreateTime(), Comparator.nullsLast(Comparator.reverseOrder())));
        return conversations;
    }

    @Override
    public PageResult<MessageVO> pageMessages(Long peerId, long page, long size, Jwt jwt) {
        Long userId = JwtSubjects.userIdOf(jwt);
        assertPeerValid(peerId, userId);
        Page<PrivateMessage> result = page(new Page<>(page, size),
                Wrappers.lambdaQuery(PrivateMessage.class)
                        // 双方消息整组嵌套：(我→他) OR (他→我)，避免顶层 OR 使逻辑删除条件只作用于第一组
                        .and(outer -> outer
                                .and(w -> w.eq(PrivateMessage::getSenderId, userId)
                                        .eq(PrivateMessage::getReceiverId, peerId))
                                .or(w -> w.eq(PrivateMessage::getSenderId, peerId)
                                        .eq(PrivateMessage::getReceiverId, userId)))
                        .orderByDesc(PrivateMessage::getCreateTime)
                        .orderByDesc(PrivateMessage::getId));
        List<MessageVO> records = result.getRecords().stream().map(this::toMessageVO).toList();
        return PageResult.of(records, result.getTotal(), page, size);
    }

    @Override
    public Long send(MessageSendDTO dto, Jwt jwt) {
        Long userId = JwtSubjects.userIdOf(jwt);
        Long receiverId = dto.getReceiverId();
        assertPeerValid(receiverId, userId);
        String content = dto.getContent();
        if (Objects.isNull(content) || content.isBlank() || content.length() > CONTENT_MAX_LENGTH) {
            throw new BusinessException(StatusCode.MESSAGE_CONTENT_INVALID);
        }
        if (Objects.isNull(userService.getById(receiverId))) {
            throw new BusinessException(StatusCode.USER_NOT_EXIST);
        }
        PrivateMessage message = new PrivateMessage();
        message.setSenderId(userId);
        message.setReceiverId(receiverId);
        message.setContent(content);
        message.setReadFlag(UNREAD);
        save(message);
        // 通知接收者（summary = 内容前 50 字；失败不影响主业务）
        String summary = content.length() <= SUMMARY_MAX_LENGTH
                ? content : content.substring(0, SUMMARY_MAX_LENGTH);
        notificationService.notify(receiverId, NotificationType.PRIVATE_MESSAGE, userId,
                null, null, summary, false);
        return message.getId();
    }

    @Override
    public void markRead(Long peerId, Jwt jwt) {
        Long userId = JwtSubjects.userIdOf(jwt);
        assertPeerValid(peerId, userId);
        update(Wrappers.lambdaUpdate(PrivateMessage.class)
                .set(PrivateMessage::getReadFlag, READ)
                .eq(PrivateMessage::getReceiverId, userId)
                .eq(PrivateMessage::getSenderId, peerId)
                .eq(PrivateMessage::getReadFlag, UNREAD));
    }

    /**
     * 会话对方校验：非空且不能是自己
     */
    private void assertPeerValid(Long peerId, Long userId) {
        if (Objects.isNull(peerId) || peerId.equals(userId)) {
            throw new BusinessException(StatusCode.MESSAGE_PEER_INVALID);
        }
    }

    private Long peerIdOf(PrivateMessage message, Long userId) {
        return userId.equals(message.getSenderId()) ? message.getReceiverId() : message.getSenderId();
    }

    private MessageVO toMessageVO(PrivateMessage message) {
        MessageVO vo = new MessageVO();
        vo.setId(message.getId());
        vo.setSenderId(message.getSenderId());
        vo.setReceiverId(message.getReceiverId());
        vo.setContent(message.getContent());
        vo.setReadFlag(message.getReadFlag());
        vo.setCreateTime(message.getCreateTime());
        return vo;
    }

    private SocialUserVO toSocialUserVO(User user) {
        SocialUserVO vo = new SocialUserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        return vo;
    }
}
