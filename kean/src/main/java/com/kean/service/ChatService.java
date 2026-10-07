package com.kean.service;

import com.kean.common.PageResult;
import com.kean.vo.ChatMessageVO;
import com.kean.vo.ChatSessionVO;

import java.util.List;

public interface ChatService {

    List<ChatSessionVO> listMine();

    ChatSessionVO open(Long peerUserId);

    ChatSessionVO detail(Long sessionId);

    PageResult<ChatMessageVO> messages(Long sessionId, Long page, Long size);

    ChatMessageVO send(Long sessionId, String msgType, String content);

    void markRead(Long sessionId);

    long unreadCount();
}
