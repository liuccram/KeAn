package com.kean.controller;

import com.kean.common.PageResult;
import com.kean.common.Result;
import com.kean.dto.CreateChatRequest;
import com.kean.dto.SendChatMessageRequest;
import com.kean.service.ChatService;
import com.kean.vo.ChatMessageVO;
import com.kean.vo.ChatPeerVO;
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

    @GetMapping("/peers")
    public Result<List<ChatPeerVO>> peers(@RequestParam(required = false) String keyword) {
        return Result.ok(chatService.listPeers(keyword));
    }

    @GetMapping("/{id}")
    public Result<ChatSessionVO> detail(@PathVariable Long id) {
        return Result.ok(chatService.detail(id));
    }

    @GetMapping("/{id}/messages")
    public Result<PageResult<ChatMessageVO>> messages(
            @PathVariable Long id,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size
    ) {
        return Result.ok(chatService.messages(id, page, size));
    }

    @PostMapping("/{id}/messages")
    public Result<ChatMessageVO> send(@PathVariable Long id, @Valid @RequestBody SendChatMessageRequest request) {
        return Result.ok(chatService.send(id, request.msgType(), request.content()));
    }

    @PostMapping("/{id}/read")
    public Result<Void> markRead(@PathVariable Long id) {
        chatService.markRead(id);
        return Result.ok();
    }
}
