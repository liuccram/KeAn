package com.kean.controller;

import com.kean.common.PageResult;
import com.kean.common.Result;
import com.kean.dto.CreateChatRequest;
import com.kean.dto.MarkChatReadRequest;
import com.kean.dto.SendChatMessageRequest;
import com.kean.service.ChatService;
import com.kean.vo.ChatMessageVO;
import com.kean.vo.ChatSessionVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/chats")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping
    public Result<List<ChatSessionVO>> list() {
        return Result.ok(chatService.listMine());
    }

    @PostMapping
    public Result<ChatSessionVO> open(@Valid @RequestBody CreateChatRequest request) {
        return Result.ok(chatService.open(request.peerUserId()));
    }

    @GetMapping("/unread-count")
    public Result<Long> unreadCount() {
        return Result.ok(chatService.unreadCount());
    }

    @GetMapping("/{id}")
    public Result<ChatSessionVO> detail(@PathVariable Long id) {
        return Result.ok(chatService.detail(id));
    }

    /**
     * 会话消息。
     *
     * <ul>
     *     <li>不带 afterSeq：历史分页，page/size 生效（老客户端行为不变）。</li>
     *     <li>带 afterSeq：增量拉取 seq_no &gt; afterSeq 的消息，升序，忽略 page/size。</li>
     * </ul>
     */
    @GetMapping("/{id}/messages")
    public Result<PageResult<ChatMessageVO>> messages(
            @PathVariable Long id,
            @RequestParam(required = false) Long afterSeq,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size
    ) {
        return Result.ok(chatService.messages(id, afterSeq, page, size));
    }

    @PostMapping("/{id}/messages")
    public Result<ChatMessageVO> send(@PathVariable Long id, @Valid @RequestBody SendChatMessageRequest request) {
        return Result.ok(chatService.send(id, request.resolvedMsgType(), request.content(), request.localId()));
    }

    /**
     * 标记已读到 maxSeq。maxSeq 同时支持请求体 {@code {"maxSeq": 35}} 和查询参数
     * {@code ?maxSeq=35}（老客户端两者都不传时保持原行为）。
     */
    @PostMapping("/{id}/read")
    public Result<Void> markRead(
            @PathVariable Long id,
            @RequestParam(required = false) Long maxSeq,
            @RequestBody(required = false) MarkChatReadRequest request
    ) {
        Long seq = maxSeq;
        if (seq == null && request != null) {
            seq = request.maxSeq();
        }
        chatService.markRead(id, seq);
        return Result.ok();
    }
}
