package com.kean.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kean.chat.ChatWebSocketHandler;
import com.kean.chat.RealtimePublisher;
import com.kean.common.ErrorCode;
import com.kean.common.PageResult;
import com.kean.entity.ChatMessage;
import com.kean.entity.ChatSession;
import com.kean.entity.SysUser;
import com.kean.enums.ChatMessageStatus;
import com.kean.enums.UserRole;
import com.kean.enums.UserStatus;
import com.kean.exception.BizException;
import com.kean.im.ImSenderService;
import com.kean.mapper.ChatMessageMapper;
import com.kean.mapper.ChatSessionMapper;
import com.kean.mapper.SysUserMapper;
import com.kean.security.SecurityUtils;
import com.kean.service.BlacklistService;
import com.kean.service.ChatSeqService;
import com.kean.service.ChatService;
import com.kean.utils.FileUrls;
import com.kean.utils.UserRestrictions;
import com.kean.vo.ChatMessageVO;
import com.kean.vo.ChatSessionVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    private static final long DEFAULT_PAGE = 1L;
    private static final long DEFAULT_SIZE = 20L;
    private static final long MAX_SIZE = 50L;

    /**
     * afterSeq 增量拉取模式下单次返回的条数上限。
     * 增量拉取不是"翻页"而是"追进度"，所以这里不用 MAX_SIZE（50），
     * 但也不能无上限：客户端拿到 200 条后应继续用返回的最后一个 seqNo 再拉一次。
     */
    private static final int CATCH_UP_LIMIT = 200;

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final SysUserMapper sysUserMapper;
    private final ChatWebSocketHandler chatWebSocketHandler;
    private final BlacklistService blacklistService;
    private final ChatSeqService chatSeqService;
    private final RealtimePublisher realtimePublisher;
    /**
     * box-im im-server 的镜像投递（阶段 3 新增，<b>纯追加</b>）。
     *
     * <p>它只在「课安自己的 {@link ChatWebSocketHandler} 推完之后」再多写一份 Redis 队列；
     * {@code IM_JWT_SECRET} 未配置时 {@link ImSenderService#sendPrivate} 内部直接 return，
     * 因此开关关闭时本类的行为与新增之前逐字节一致。</p>
     */
    private final ImSenderService imSenderService;

    public ChatServiceImpl(
            ChatSessionMapper chatSessionMapper,
            ChatMessageMapper chatMessageMapper,
            SysUserMapper sysUserMapper,
            ChatWebSocketHandler chatWebSocketHandler,
            BlacklistService blacklistService,
            ChatSeqService chatSeqService,
            RealtimePublisher realtimePublisher,
            ImSenderService imSenderService
    ) {
        this.chatSessionMapper = chatSessionMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.sysUserMapper = sysUserMapper;
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.blacklistService = blacklistService;
        this.chatSeqService = chatSeqService;
        this.realtimePublisher = realtimePublisher;
        this.imSenderService = imSenderService;
    }

    @Override
    public List<ChatSessionVO> listMine() {
        Long userId = SecurityUtils.currentUserId();
        List<ChatSession> sessions = chatSessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                .and(w -> w.eq(ChatSession::getUserAId, userId).or().eq(ChatSession::getUserBId, userId))
                .orderByDesc(ChatSession::getLastMessageAt)
                .orderByDesc(ChatSession::getId));
        Set<Long> blocked = blacklistService.relatedUserIds(userId);
        return sessions.stream()
                .filter(session -> {
                    Long peerId = Objects.equals(session.getUserAId(), userId) ? session.getUserBId() : session.getUserAId();
                    return !blocked.contains(peerId);
                })
                .map(session -> toSessionVo(session, userId))
                .toList();
    }

    @Override
    @Transactional
    public ChatSessionVO open(Long peerUserId) {
        Long userId = SecurityUtils.currentUserId();
        if (Objects.equals(userId, peerUserId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能和自己聊天");
        }
        SysUser me = requireUser(userId);
        SysUser peer = requirePeer(peerUserId);
        // 发起私信不再限制学校/校区：任何人都可以发起。已有的会话依旧走下面的 findSession 复用逻辑，
        // 会话列表、收发消息、未读、WebSocket 链路都没有学校判断。
        blacklistService.assertCanInteract(userId, peerUserId);
        ChatSession existed = findSession(userId, peerUserId);
        if (existed == null && peer.getPrivateAccount() != null && peer.getPrivateAccount() == 1) {
            throw new BizException(ErrorCode.FORBIDDEN, "对方已设置隐私账号，无法发起私聊");
        }
        ChatSession session = existed == null ? createSession(userId, peerUserId) : existed;
        return toSessionVo(session, userId);
    }

    @Override
    public ChatSessionVO detail(Long sessionId) {
        Long userId = SecurityUtils.currentUserId();
        return toSessionVo(requireOwnedSession(sessionId, userId), userId);
    }

    /**
     * 两种取消息模式，互斥：
     *
     * <ol>
     *     <li><b>历史分页（老客户端）</b>：不带 {@code afterSeq} 时走老逻辑——
     *         按 id 倒序取第 page 页、返回前翻转成时间升序，total 是会话消息总数。
     *         与改动前逐字节一致，老客户端（uni-kean 现有 implementation 只传 page/size）不受影响。</li>
     *     <li><b>增量拉取（box 语义）</b>：带 {@code afterSeq} 时返回 {@code seq_no > afterSeq}
     *         的消息，按 seq_no 升序、单次上限 {@link #CATCH_UP_LIMIT} 条，忽略 page/size。
     *         增量模式下 total 取本次返回条数（不再做 COUNT，少一次全表扫描）。</li>
     * </ol>
     */
    @Override
    public PageResult<ChatMessageVO> messages(Long sessionId, Long afterSeq, Long page, Long size) {
        Long userId = SecurityUtils.currentUserId();
        requireOwnedSession(sessionId, userId);
        if (afterSeq != null) {
            return catchUp(sessionId, afterSeq, userId);
        }
        long pageNo = page == null || page < 1 ? DEFAULT_PAGE : page;
        long pageSize = size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        Page<ChatMessage> result = chatMessageMapper.selectPage(
                new Page<>(pageNo, pageSize),
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByDesc(ChatMessage::getId)
        );
        List<ChatMessageVO> list = new ArrayList<>(result.getRecords().stream()
                .map(item -> toMessageVo(item, userId))
                .toList());
        Collections.reverse(list);
        return new PageResult<>(list, result.getTotal(), pageNo, pageSize);
    }

    private PageResult<ChatMessageVO> catchUp(Long sessionId, long afterSeq, Long userId) {
        // seq_no > afterSeq：seq_no 为 NULL 的历史行天然不参与（NULL 与任何值比较都不成立），
        // 与 box 侧"历史消息靠 seq_no 游标"的语义一致。没有 seq_no 的历史数据仍能用历史分页模式拿到。
        List<ChatMessage> records = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .gt(ChatMessage::getSeqNo, afterSeq)
                .orderByAsc(ChatMessage::getSeqNo)
                .last("LIMIT " + CATCH_UP_LIMIT));
        List<ChatMessageVO> list = records.stream()
                .map(item -> toMessageVo(item, userId))
                .toList();
        return new PageResult<>(list, list.size(), DEFAULT_PAGE, list.size());
    }

    @Override
    @Transactional
    public ChatMessageVO send(Long sessionId, String msgType, String content, String localId) {
        Long userId = SecurityUtils.currentUserId();
        SysUser me = requireUser(userId);
        if (UserRestrictions.muted(me)) {
            throw new BizException(ErrorCode.ACCOUNT_MUTED);
        }
        String type = !StringUtils.hasText(msgType) ? "TEXT" : msgType.trim().toUpperCase();
        if (!"TEXT".equals(type) && !"IMAGE".equals(type)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "消息类型不正确");
        }
        String text = content == null ? "" : content.trim();
        if (!StringUtils.hasText(text)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "消息不能为空");
        }
        if ("IMAGE".equals(type)) {
            String key = FileUrls.objectKey(text);
            if (key == null || !key.startsWith("chat/" + userId + "/")) {
                throw new BizException(ErrorCode.BAD_REQUEST, "图片文件无效");
            }
            text = key;
        }
        // 幂等键：老客户端不传 localId 时服务端补一个（保证唯一索引 (sender_id, local_id) 不冲突且不重号）。
        String idemKey = StringUtils.hasText(localId) ? localId.trim() : generatedLocalId();

        // 幂等重发：同一 (senderId, localId) 已经落过库就直接返回那一条，不再插入、不再分配新 seq_no，
        // 也不重复推 WebSocket / 重复加未读。这样客户端重试不会产生第二条消息，也不会 500。
        ChatMessage existed = findByLocalId(userId, idemKey);
        if (existed != null) {
            return toMessageVo(existed, userId);
        }

        ChatSession session = requireOwnedSession(sessionId, userId);
        Long peerId = Objects.equals(session.getUserAId(), userId) ? session.getUserBId() : session.getUserAId();
        SysUser peer = sysUserMapper.selectById(peerId);
        if (peer != null && UserStatus.BANNED.name().equals(peer.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "对方已被封禁");
        }
        blacklistService.assertCanInteract(userId, peerId);

        long seqNo = chatSeqService.allocateNext(sessionId, session.getLastSeqNo());
        ChatMessage message = new ChatMessage();
        message.setSessionId(session.getId());
        message.setSeqNo(seqNo);
        message.setLocalId(idemKey);
        message.setSenderId(userId);
        message.setMsgType(type);
        message.setContent(text);
        // box 语义：新消息 = 1（已发送）。0（未读）在课安由会话位点表达，不写库。
        message.setStatus(ChatMessageStatus.SENT.code());
        try {
            chatMessageMapper.insert(message);
        } catch (DuplicateKeyException dup) {
            // 唯一键兜底：并发重试时两个请求都通过了上面的预检，后插入的那个撞
            // uk_chat_message_sender_local。这里不抛 500，查回已存在的那条返回即可。
            ChatMessage winner = findByLocalId(userId, idemKey);
            if (winner != null) {
                return toMessageVo(winner, userId);
            }
            if (findBySeqNo(sessionId, seqNo) != null) {
                // 极少数情况：撞的是 (session_id, seq_no)——Redis 序列表被外部清空后重播种。
                // 让用户重试一次而不是返回 50000。
                throw new BizException(ErrorCode.BAD_REQUEST, "消息发送冲突，请重试");
            }
            throw dup;
        }

        boolean senderIsA = Objects.equals(session.getUserAId(), userId);
        session.setLastMessageAt(LocalDateTime.now());
        String preview = "IMAGE".equals(type) ? "[图片]" : (text.length() > 200 ? text.substring(0, 200) : text);
        session.setLastContent(preview);
        // 会话维度同步最新序号（box 侧会话的 last_seq_no）；GREATEST 保证不会被并发写回退。
        session.setLastSeqNo(seqNo);
        if (senderIsA) {
            session.setBUnread((session.getBUnread() == null ? 0 : session.getBUnread()) + 1);
        } else {
            session.setAUnread((session.getAUnread() == null ? 0 : session.getAUnread()) + 1);
        }
        chatSessionMapper.updateById(session);
        // 发送方自己显然读到了自己的消息，把"我自己"的已读位点也推到该条，
        // 这样对方从会话详情拿到的读位点不会出现"我发的消息里自己的位点是空的"。
        // 只影响读位点，不参与未读计数口径。
        if (senderIsA) {
            if (session.getAReadSeq() == null || session.getAReadSeq() < seqNo) {
                session.setAReadSeq(seqNo);
            }
        } else if (session.getBReadSeq() == null || session.getBReadSeq() < seqNo) {
            session.setBReadSeq(seqNo);
        }

        // 保留既有 WebSocket 链路：仍推 MESSAGE 事件，只是 payload 里多了 seqNo/localId/status/readAt。
        chatWebSocketHandler.pushMessage(peerId, toMessageVo(message, peerId));

        // 阶段 3：在既有推送「之后」再镜像一份到 box-im im-server 的私聊队列
        // （im:message:private:{serverId}）。这里是纯追加：
        //   · IM_JWT_SECRET 未配置 → sendPrivate 内部第一行就 return，不触 Redis；
        //   · 接收方不在 im-server 上 → 只留 DEBUG 日志；
        //   · 任何异常都在 sendPrivate 内部被 try/catch 吃掉，只 log.warn，绝不外抛，
        //     因此不会让本方法所在的 @Transactional 事务回滚。
        // 第 3 个参数是 sessionId：镜像 data 里必须带它，否则客户端 applyIncoming()
        // 会因为 sessionId 对不上而直接丢弃这条消息（气泡不显示，只剩角标）。
        // 最后一个参数是入库后的 createdAt（AuditMetaObjectHandler 填的），
        // 让镜像里的时间与 HTTP 路径返回的 ChatMessageVO.createdAt 完全一致。
        imSenderService.sendPrivate(peerId, userId, session.getId(), type, text, idemKey, seqNo,
                message.getCreatedAt());

        return toMessageVo(message, userId);
    }

    /**
     * 已读位点（box 语义）。
     *
     * <p>收到 maxSeq 时：
     * <ol>
     *     <li>按 V1 的 user_a_id(较小) / user_b_id(较大) 约定，写自己那一侧的 a_read_seq / b_read_seq，
     *         只前进不回退（GREATEST 幂等），顺带把自己那侧未读数清零；</li>
     *     <li>把该会话中"对方发给我的、seq_no &lt;= maxSeq"的消息 status 置 3（已读）并写 read_at；</li>
     *     <li>通过既有 RealtimePublisher 给<b>对方</b>推一个 READ 事件（新事件类型）。</li>
     * </ol>
     *
     * <p>不传 maxSeq（老客户端）时保持改动前的行为：只把自己那一侧未读数清零、不推事件，
     * 也不分配/推进任何 seq 位点。这条分支纯粹是向后兼容，新客户端请始终带 maxSeq。
     */
    @Override
    @Transactional
    public void markRead(Long sessionId, Long maxSeq) {
        Long userId = SecurityUtils.currentUserId();
        ChatSession session = requireOwnedSession(sessionId, userId);
        boolean userIsA = Objects.equals(session.getUserAId(), userId);
        if (maxSeq == null) {
            // 老客户端路径：只清自己那侧未读数。用定向 UPDATE 而不是 updateById(full entity)，
            // 避免把本次请求开始时读到的 a_read_seq/b_read_seq 旧值写回去、覆盖对方的并发已读。
            LambdaUpdateWrapper<ChatSession> unreadReset = new LambdaUpdateWrapper<ChatSession>()
                    .eq(ChatSession::getId, sessionId);
            if (userIsA) {
                unreadReset.set(ChatSession::getAUnread, 0);
            } else {
                unreadReset.set(ChatSession::getBUnread, 0);
            }
            chatSessionMapper.update(null, unreadReset);
            return;
        }

        long cursor = Math.max(maxSeq, 0L);
        LocalDateTime now = LocalDateTime.now();
        // 1) 读位点 + 未读清零：一条 UPDATE 搞定。
        //    位点用 setSql + GREATEST 而不是「读出来比较再写」，并发 markRead 时只会前进不会回退
        //    （COALESCE 兼容历史会话 a_read_seq/b_read_seq 为 NULL）。
        //    未读口径保持现状（不动计数逻辑），只在带入 maxSeq 的路径上顺带清零，
        //    避免新客户端"已写了位点但角标还在"。
        LambdaUpdateWrapper<ChatSession> cursorUpdate = new LambdaUpdateWrapper<ChatSession>()
                .eq(ChatSession::getId, sessionId);
        if (userIsA) {
            cursorUpdate.setSql("a_read_seq = GREATEST(COALESCE(a_read_seq, 0), " + cursor + ")")
                    .set(ChatSession::getAUnread, 0);
        } else {
            cursorUpdate.setSql("b_read_seq = GREATEST(COALESCE(b_read_seq, 0), " + cursor + ")")
                    .set(ChatSession::getBUnread, 0);
        }
        chatSessionMapper.update(null, cursorUpdate);

        // 2) 对方发给我、且 seq_no <= maxSeq 的消息置为已读。
        //    senderId != userId 同时挡掉自己发的消息；seq_no 为 NULL 的历史行不参与。
        chatMessageMapper.update(null, new LambdaUpdateWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .ne(ChatMessage::getSenderId, userId)
                .le(ChatMessage::getSeqNo, cursor)
                .ne(ChatMessage::getStatus, ChatMessageStatus.READ.code())
                .set(ChatMessage::getStatus, ChatMessageStatus.READ.code())
                .set(ChatMessage::getReadAt, now));

        // 3) READ 事件推给对方（readerId = 当前用户）。写法与既有 NOTICE 一致：
        //    RealtimePublisher 内部已吞掉"对方不在线"的异常，不影响本次标记已读的结果。
        Long peerId = userIsA ? session.getUserBId() : session.getUserAId();
        if (peerId != null && !Objects.equals(peerId, userId)) {
            realtimePublisher.read(peerId, sessionId, cursor, userId);
        } else {
            log.debug("skip READ push, no peer. sessionId={}", sessionId);
        }
    }

    @Override
    public long unreadCount() {
        Long userId = SecurityUtils.currentUserId();
        List<ChatSession> sessions = chatSessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                .and(w -> w.eq(ChatSession::getUserAId, userId).or().eq(ChatSession::getUserBId, userId)));
        long total = 0;
        for (ChatSession session : sessions) {
            Integer unread = Objects.equals(session.getUserAId(), userId) ? session.getAUnread() : session.getBUnread();
            total += unread == null ? 0 : unread;
        }
        return total;
    }

    private ChatMessage findByLocalId(Long senderId, String localId) {
        if (!StringUtils.hasText(localId)) {
            return null;
        }
        return chatMessageMapper.selectOne(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSenderId, senderId)
                .eq(ChatMessage::getLocalId, localId));
    }

    private ChatMessage findBySeqNo(Long sessionId, long seqNo) {
        return chatMessageMapper.selectOne(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .eq(ChatMessage::getSeqNo, seqNo));
    }

    /**
     * 服务端兜底生成的 localId：32 位无连字符 UUID，正好贴满 VARCHAR(32)。
     * 与客户端生成的 id 不会撞：客户端一般用 "c" + 时间戳 + 随机数，这里也会被
     * (sender_id, local_id) 唯一索引兜底。
     */
    private String generatedLocalId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private ChatSession findSession(Long userId, Long peerUserId) {
        long a = Math.min(userId, peerUserId);
        long b = Math.max(userId, peerUserId);
        return chatSessionMapper.selectOne(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getUserAId, a)
                .eq(ChatSession::getUserBId, b));
    }

    private ChatSession createSession(Long userId, Long peerUserId) {
        long a = Math.min(userId, peerUserId);
        long b = Math.max(userId, peerUserId);
        ChatSession session = new ChatSession();
        session.setUserAId(a);
        session.setUserBId(b);
        session.setAUnread(0);
        session.setBUnread(0);
        chatSessionMapper.insert(session);
        return session;
    }

    private ChatSession requireOwnedSession(Long sessionId, Long userId) {
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BizException(ErrorCode.CHAT_NOT_FOUND);
        }
        if (!Objects.equals(session.getUserAId(), userId) && !Objects.equals(session.getUserBId(), userId)) {
            throw new BizException(ErrorCode.CHAT_NOT_FOUND);
        }
        return session;
    }

    private SysUser requireUser(Long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (UserStatus.BANNED.name().equals(user.getStatus())) {
            throw new BizException(ErrorCode.ACCOUNT_BANNED);
        }
        return user;
    }

    private SysUser requirePeer(Long peerUserId) {
        SysUser peer = sysUserMapper.selectById(peerUserId);
        if (peer == null || !UserRole.USER.name().equals(peer.getRole())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "聊天对象不存在");
        }
        if (UserStatus.BANNED.name().equals(peer.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "对方已被封禁");
        }
        return peer;
    }

    private ChatSessionVO toSessionVo(ChatSession session, Long userId) {
        Long peerId = Objects.equals(session.getUserAId(), userId) ? session.getUserBId() : session.getUserAId();
        SysUser peer = sysUserMapper.selectById(peerId);
        Integer unread = Objects.equals(session.getUserAId(), userId) ? session.getAUnread() : session.getBUnread();
        return new ChatSessionVO(
                session.getId(),
                peerId,
                peer == null ? "同学" : peer.getNickname(),
                peer == null ? null : FileUrls.of(peer.getAvatarUrl()),
                session.getLastContent(),
                session.getLastMessageAt(),
                unread == null ? 0 : unread,
                UserRestrictions.muted(peer),
                peer != null && UserStatus.BANNED.name().equals(peer.getStatus()),
                session.getLastSeqNo()
        );
    }

    private ChatMessageVO toMessageVo(ChatMessage message, Long viewerId) {
        boolean image = "IMAGE".equals(message.getMsgType());
        return new ChatMessageVO(
                message.getId(),
                message.getSessionId(),
                message.getSenderId(),
                message.getMsgType(),
                message.getContent(),
                image ? FileUrls.of(message.getContent()) : null,
                message.getCreatedAt(),
                Objects.equals(message.getSenderId(), viewerId),
                message.getSeqNo(),
                message.getLocalId(),
                message.getStatus(),
                message.getReadAt()
        );
    }
}
