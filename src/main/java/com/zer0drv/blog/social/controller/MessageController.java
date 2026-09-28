package com.zer0drv.blog.social.controller;

import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.social.dto.MessageSendDTO;
import com.zer0drv.blog.social.service.MessageService;
import com.zer0drv.blog.social.vo.ConversationVO;
import com.zer0drv.blog.social.vo.MessageVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 一对一私信（v1 轮询，全部需登录）
 *
 * @author Yoruhaki
 */
@RestController
@RequestMapping("/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    /**
     * 会话列表（每会话最新一条 + 未读数，按最新一条时间倒序）
     */
    @GetMapping("/conversations")
    @PreAuthorize("isAuthenticated()")
    public Result<List<ConversationVO>> conversations(@AuthenticationPrincipal Jwt jwt) {
        return Result.ok(messageService.conversations(jwt));
    }

    /**
     * 与某人的消息分页（create_time 倒序返回，前端倒转展示）
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Result<PageResult<MessageVO>> pageMessages(@RequestParam Long peerId,
                                                      @RequestParam(defaultValue = "1") long page,
                                                      @RequestParam(defaultValue = "20") long size,
                                                      @AuthenticationPrincipal Jwt jwt) {
        return Result.ok(messageService.pageMessages(peerId, page, size, jwt));
    }

    /**
     * 发送私信（不能发给自己；内容 ≤1000）
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public Result<Long> send(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid MessageSendDTO dto) {
        return Result.ok(messageService.send(dto, jwt));
    }

    /**
     * 把该会话中发给我的未读消息全部标记已读
     */
    @PutMapping("/read")
    @PreAuthorize("isAuthenticated()")
    public Result<Void> markRead(@AuthenticationPrincipal Jwt jwt, @RequestParam Long peerId) {
        messageService.markRead(peerId, jwt);
        return Result.ok();
    }
}
