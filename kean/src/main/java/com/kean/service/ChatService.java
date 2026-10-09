package com.kean.service;

import com.kean.common.PageResult;
import com.kean.vo.ChatMessageVO;
import com.kean.vo.ChatSessionVO;

import java.util.List;

public interface ChatService {

    List<ChatSessionVO> listMine();

    ChatSessionVO open(Long peerUserId);

    ChatSessionVO detail(Long sessionId);

    /**
     * 拉取会话消息。两种模式：
     * <ul>
     *     <li>{@code afterSeq == null}：历史分页（老行为，page/size 生效）；</li>
     *     <li>{@code afterSeq != null}：增量拉取，返回 seq_no &gt; afterSeq 的消息（升序，忽略 page/size）。</li>
     * </ul>
     */
    PageResult<ChatMessageVO> messages(Long sessionId, Long afterSeq, Long page, Long size);

    /**
     * 发送消息。{@code localId} 可空（老客户端），为空时服务端生成；
     * 同一 (senderId, localId) 重复提交返回已存在的那条，不重复落库。
     */
    ChatMessageVO send(Long sessionId, String msgType, String content, String localId);

    /**
     * 标记已读。{@code maxSeq} 为读到的位点：
     * 非空时写 a_read_seq/b_read_seq、把对方发给我的 seq_no &lt;= maxSeq 的消息置为已读、并推 READ 事件；
     * 为空时保持老行为（只清未读数，不动位点、不推事件）。
     */
    void markRead(Long sessionId, Long maxSeq);

    long unreadCount();
}
