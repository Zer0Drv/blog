package com.zer0drv.blog.social.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.social.domain.PrivateMessage;
import com.zer0drv.blog.social.dto.MessageSendDTO;
import com.zer0drv.blog.social.vo.ConversationVO;
import com.zer0drv.blog.social.vo.MessageVO;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * 一对一私信（v1 轮询）
 *
 * @author Yoruhaki
 */
public interface MessageService extends IService<PrivateMessage> {

    /**
     * 会话列表：每会话最新一条 + 未读数，按最新一条时间倒序
     */
    List<ConversationVO> conversations(Jwt jwt);

    /**
     * 与某人的消息分页（create_time 倒序；peerId 不能是自己）
     */
    PageResult<MessageVO> pageMessages(Long peerId, long page, long size, Jwt jwt);

    /**
     * 发送私信（不能发给自己；内容 ≤1000）
     */
    Long send(MessageSendDTO dto, Jwt jwt);

    /**
     * 把该会话中发给我的未读消息全部标记已读（peerId 不能是自己）
     */
    void markRead(Long peerId, Jwt jwt);
}
