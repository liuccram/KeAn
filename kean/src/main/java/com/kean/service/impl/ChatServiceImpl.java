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
import com.kean.im.ImMessageMirrorService;
import com.kean.im.ImMultiTerminalEchoService;
import com.kean.im.ImOfflineQueryService;
import com.kean.im.ImSenderService;
import com.kean.im.ImTerminalResolver;
import com.kean.im.ImUnreadQueryService;
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
import java.util.Map;
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

    /**
     * 阶段 B-1：把 kean 的消息「物化镜像」进 box 的 {@code im_platform.im_private_message}
     * （<b>纯追加</b>，与上面的 {@link ImSenderService} 是两条互不相干的通道 ——
     * 那条写 Redis 队列给 im-server 推客户端，这条写 MySQL 表给 im-platform 读）。
     *
     * <p>默认关闭（{@code kean.im.message-mirror-enabled=false}）：关闭时
     * {@link ImMessageMirrorService#mirrorQuietly} 第一行就 return，
     * <b>一条 SQL 都不发</b>，本类的行为与新增之前逐字节一致。</p>
     *
     * <p>⚠️ 它在 {@link #send} 的 {@code @Transactional} 体内被调用，但
     * <b>不会影响事务语义</b>：镜像方法自己 try/catch 掉一切异常并返回 boolean，
     * <b>永不外抛</b>；而且它内部用 {@code REQUIRES_NEW} 把 box 的写挂到<b>独立事务</b>上
     * （见 {@code ImMessageMirrorService} 类注释「失败策略」），
     * 所以 box 写失败不可能回滚 kean 已经落库的消息（用户明确选择「kean 为主」）。</p>
     */
    private final ImMessageMirrorService imMessageMirrorService;

    /**
     * 阶段 B-3：未读/已读的<b>来源切换</b>（{@code kean.im.unread-source}，默认 {@code kean}）。
     *
     * <p>它只在开关为 {@code box} 时说话，且<b>只读写 box 的 {@code im_platform.im_private_message}</b>：</p>
     * <ul>
     *   <li>{@link #listMine} / {@link #unreadCount} / {@link #detail} 的未读数改由它计算
     *       （按 box 的 {@code status < 3} 数条数）；</li>
     *   <li>{@link #markRead} 在写 kean 位点之前，先通过它把 box 侧该会话中
     *       「发给我的、seq_no &lt;= maxSeq」的行置成已读。</li>
     * </ul>
     * <p>⚠️ 两条硬约定（见 {@link ImUnreadQueryService} 类注释）：</p>
     * <ol>
     *   <li><b>它绝不替本类做决定</b>：查不出/写不成时返回 {@code null} / {@code false}，
     *       本类<b>照旧</b>维护 kean 的 {@code a_unread/b_unread}、位点与
     *       {@code chat_message.status=3} —— 这是「回退时数据是热的」的唯一保证；</li>
     *   <li>开关为 {@code kean}（默认）时它<b>一条 SQL 都不发</b>，
     *       本类的行为与新增它之前逐字节一致。</li>
     * </ol>
     */
    private final ImUnreadQueryService imUnreadQueryService;

    /**
     * 阶段 C-2：增量拉取的<b>读取来源切换</b>（{@code kean.im.read-source}，默认 {@code kean}）。
     *
     * <p>它只在开关为 {@code box} 时说话，且<b>只读</b> box 的
     * {@code im_platform.im_private_message}：{@link #catchUp} 会先问它要一批消息，
     * 它返回 {@code null}（开关未开 / 数据源不可用 / 该用户不是会话参与者 / 查询失败 / box 侧没查到）
     * 时本类<b>照旧</b>走既有的 {@code afterSeq} 实现 —— 这就是「box 挂了聊天页照样能打开」的落点。</p>
     *
     * <p>⚠️ 它与 B-3 的 {@link #imUnreadQueryService} 是<b>两个独立开关</b>（不合并）：
     * 一个管「消息内容从哪读」，一个管「未读数从哪算 / 已读往哪写」，便于分别灰度与分别回退。</p>
     */
    private final ImOfflineQueryService imOfflineQueryService;

    /**
     * 阶段 C-3：把消息同步给发送者<b>自己的其它终端</b>（{@code kean.im.multi-terminal-echo-enabled}，
     * 默认 <b>false</b>）—— box 的 {@code sendToSelf} 语义。
     *
     * <p>它只在 {@link #send} 里、<b>kean 的消息落库之后</b>被追加调用一次（位置与 B-1 的镜像相邻）；
     * 开关关闭时它第一行就返回 0，<b>不读 Redis、不写队列</b>，本类的行为与新增它之前逐字节一致。
     * 失败也只 WARN + 计数，绝不影响发送（见 {@link ImMultiTerminalEchoService} 类注释）。</p>
     */
    private final ImMultiTerminalEchoService imMultiTerminalEchoService;

    public ChatServiceImpl(
            ChatSessionMapper chatSessionMapper,
            ChatMessageMapper chatMessageMapper,
            SysUserMapper sysUserMapper,
            ChatWebSocketHandler chatWebSocketHandler,
            BlacklistService blacklistService,
            ChatSeqService chatSeqService,
            RealtimePublisher realtimePublisher,
            ImSenderService imSenderService,
            ImMessageMirrorService imMessageMirrorService,
            ImUnreadQueryService imUnreadQueryService,
            ImOfflineQueryService imOfflineQueryService,
            ImMultiTerminalEchoService imMultiTerminalEchoService
    ) {
        this.chatSessionMapper = chatSessionMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.sysUserMapper = sysUserMapper;
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.blacklistService = blacklistService;
        this.chatSeqService = chatSeqService;
        this.realtimePublisher = realtimePublisher;
        this.imSenderService = imSenderService;
        this.imMessageMirrorService = imMessageMirrorService;
        this.imUnreadQueryService = imUnreadQueryService;
        this.imOfflineQueryService = imOfflineQueryService;
        this.imMultiTerminalEchoService = imMultiTerminalEchoService;
    }

    @Override
    public List<ChatSessionVO> listMine() {
        Long userId = SecurityUtils.currentUserId();
        List<ChatSession> sessions = chatSessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                .and(w -> w.eq(ChatSession::getUserAId, userId).or().eq(ChatSession::getUserBId, userId))
                .orderByDesc(ChatSession::getLastMessageAt)
                .orderByDesc(ChatSession::getId));
        Set<Long> blocked = blacklistService.relatedUserIds(userId);
        List<ChatSession> visible = sessions.stream()
                .filter(session -> {
                    Long peerId = Objects.equals(session.getUserAId(), userId) ? session.getUserBId() : session.getUserAId();
                    return !blocked.contains(peerId);
                })
                .toList();
        // 阶段 B-3：开关为 box 时，一次 SQL 把整页会话的未读算出来
        // （开关为 kean / 查询失败时这里返回空 Map，下面逐会话回退到 kean 的计数器）。
        Map<Long, Long> boxUnread = imUnreadQueryService.unreadCounts(userId, visible);
        return visible.stream()
                .map(session -> toSessionVo(session, userId, boxUnread))
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
        return toSessionVo(session, userId, boxUnreadOf(userId, List.of(session)));
    }

    @Override
    public ChatSessionVO detail(Long sessionId) {
        Long userId = SecurityUtils.currentUserId();
        ChatSession session = requireOwnedSession(sessionId, userId);
        return toSessionVo(session, userId, boxUnreadOf(userId, List.of(session)));
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
        // 阶段 C-2：先问 box 通道（开关 kean.im.read-source=box 时才真正说话）。
        // 它返回 null 的每一种情况（开关未开 / 数据源不可用 / 推不出 convKey / SQL 失败 /
        // box 侧 0 行 / box 缺行）都落到下面那段【一行未改】的既有实现上 ——
        // 这就是「box 的任何失败都不影响聊天页」的落点。
        // ⚠️ 先判 active() 再查会话：默认（read-source=kean）下本方法【一次额外的查库都不做】，
        //    行为与新增 C-2 之前逐字节一致（连 selectById 都不会发生）。
        // 注意：这里【不】把 afterSeq 直接当 box 的 minId 用，映射规则（翻译而不是赋值）见
        // ImOfflineQueryService 类注释「游标映射规则」以及 §6.3.2 第 2 步。
        if (imOfflineQueryService.active()) {
            ChatSession session = chatSessionMapper.selectById(sessionId);
            PageResult<ChatMessageVO> fromBox = imOfflineQueryService.catchUpAfterSeq(session, userId, afterSeq);
            if (fromBox != null) {
                return fromBox;
            }
        }
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

        // 阶段 B-1：把同一条消息再幂等镜像一份到 box 的 MySQL 表
        // im_platform.im_private_message（与上一条「写 Redis 队列」完全无关的第二条通道）。
        //   · 开关默认关闭（kean.im.message-mirror-enabled=false）→ 不建 JdbcTemplate、不发任何 SQL；
        //   · 位置：必须在 chatMessageMapper.insert(message) 成功之后 —— 镜像要用落库后的
        //     真实 seqNo / localId / created_at（AuditMetaObjectHandler 填的），
        //     这样 box 侧影子行与 HTTP 路径返回给客户端的 ChatMessageVO 完全同源；
        //   · 事务：镜像在【REQUIRES_NEW 的独立事务】里写 box 库（外层 kean 事务被挂起），
        //     并且内部吞掉一切异常（只 log.warn + 计数）⇒ box 写失败【不会】回滚 kean 的消息
        //     （用户明确选择「kean 为主」：box 抖一下不能让用户发不出消息）；
        //   · 这里的 try/catch 是纵深防御：即使 mirrorQuietly 将来被改成会外抛，也不会影响发送。
        try {
            imMessageMirrorService.mirrorQuietly(message, session);
        } catch (Exception ex) {
            log.warn("[IM 消息镜像] 镜像调用异常（已忽略，不影响发送），messageId={}：{}",
                    message.getId(), ex.getMessage());
        }

        // 阶段 C-3：把同一条消息按 box 的 sendToSelf 语义再投给【发送者自己的其它终端】
        // （多端同步；kean 现在的多端同步靠 8 秒增量拉取"追"，这里补上实时那一段）。
        //   · 开关默认关闭（kean.im.multi-terminal-echo-enabled=false）→ 第一行就返回 0，
        //     不读 Redis、不写队列，行为与新增之前逐字节一致；
        //   · 位置：必须在 insert 成功之后 —— data 里的 localId/seqNo/createdAt 要是落库后的真值
        //     （客户端按 localId 去重、按 seqNo 推游标，用插入前的值会造成多端重复或游标倒退）；
        //   · 当前终端由 ImTerminalResolver 推（与取 box token 时写进 JWT 的 terminal 同一份规则），
        //     它会被【跳过】，因此不会出现"自己收到自己的消息"的重复气泡；
        //   · 单端在线时它内部直接返回 0（只多一次 Redis 的 multiGet）；
        //   · 失败只在内部 log.warn + 计数，绝不影响本次发送（更不会回滚上面的落库）。
        try {
            imMultiTerminalEchoService.echoAfterSend(
                    userId,
                    session.getId(),
                    type,
                    text,
                    idemKey,
                    seqNo,
                    message.getCreatedAt(),
                    ImTerminalResolver.currentRequestTerminal());
        } catch (Exception ex) {
            log.warn("[IM 多端同步] 自我同步调用异常（已忽略，不影响发送），messageId={}：{}",
                    message.getId(), ex.getMessage());
        }

        return toMessageVo(message, userId);
    }

    /**
     * 已读位点（box 语义）。
     *
     * <p>收到 maxSeq 时：
     * <ol>
     *     <li><b>阶段 B-3（新增，仅当 {@code kean.im.unread-source=box} 时真正执行）</b>：
     *         先把 box 侧该会话中"发给我、seq_no &lt;= maxSeq"的行 status 置 3
     *         （权威侧先推进；写在 REQUIRES_NEW 的独立事务里，失败只 WARN，
     *         <b>绝不</b>影响下面的 kean 写入）；</li>
     *     <li>按 V1 的 user_a_id(较小) / user_b_id(较大) 约定，写自己那一侧的 a_read_seq / b_read_seq，
     *         只前进不回退（GREATEST 幂等），顺带把自己那侧未读数清零；</li>
     *     <li>把该会话中"对方发给我的、seq_no &lt;= maxSeq"的消息 status 置 3（已读）并写 read_at；</li>
     *     <li>通过既有 RealtimePublisher 给<b>对方</b>推一个 READ 事件（新事件类型）。</li>
     * </ol>
     * <p>⚠️ 第 1 步与第 2)/3) 步是「单向投影」关系：<b>box 是权威，但 kean 侧照旧写</b>
     * （即使 box 写失败）。目的是回退时数据是热的 —— 把开关设回 {@code kean} 后
     * 未读/已读立刻就是正确的，不需要回填（见 {@code docs/ops/im-platform-migration.md} §3.B 的 B-3 段）。</p>
     * <p>第 4 步的事件形状<b>完全没变</b>（{@code {type:"READ",sessionId,maxSeq,readerId}}），
     * 客户端一行都不用改。</p>
     *
     * <p>不传 maxSeq（老客户端）时保持改动前的行为：只把自己那一侧未读数清零、不推事件，
     * 也不分配/推进任何 seq 位点。<b>这条分支不会碰 box</b>（没有 maxSeq 就没有可投影的位点）。
     * 新客户端请始终带 maxSeq。
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

        // 0) 阶段 B-3：box 优先 —— 先把权威侧（box 的消息 status）推进到 maxSeq。
        //    顺序刻意是「先 box 后 kean」（见 ImUnreadQueryService.markReadInBox 的注释）：
        //      · box 是权威，先写它能让中间态只出现在「可自愈」的方向上（同一条 UPDATE 幂等重放）；
        //      · 它在 REQUIRES_NEW 的独立事务里，且异常被本服务内部吞掉（只返回 false + WARN）
        //        ⇒ box 挂了/表没了/没权限，都不会污染下面 kean 的 @Transactional 事务；
        //      · 无论它返回 true 还是 false，下面的 1)/2)/3) 一步都不会少
        //        ⇒ kean 的位点、chat_message.status、a_unread/b_unread 始终是「热」的，回退可用；
        //      · 开关 kean.im.unread-source=kean（默认）时它第一行就 return false，一条 SQL 都不发。
        //    这里的 try/catch 是纵深防御：即使 markReadInBox 将来被改成会外抛，
        //    「用户点开会话却报错」这条路径也不会出现。
        try {
            imUnreadQueryService.markReadInBox(userId, session, cursor);
        } catch (Exception ex) {
            log.warn("[IM 未读来源] box 已读回写调用异常（已忽略，kean 侧照旧写入），sessionId={}，maxSeq={}：{}",
                    sessionId, cursor, ex.getMessage());
        }

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
        // 阶段 B-3：与 listMine 用同一套口径（一次 SQL 算整批），逐会话回退。
        // ⚠️ 这里刻意不再像改动前那样直接读 a_unread/b_unread，而是统一走 unreadOf，
        //    否则会出现「列表角标按 box、总数角标按 kean」的两套口径（那正是本轮要消灭的分叉）。
        Map<Long, Long> boxUnread = imUnreadQueryService.unreadCounts(userId, sessions);
        long total = 0;
        for (ChatSession session : sessions) {
            total += unreadOf(session, userId, boxUnread);
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

    private ChatSessionVO toSessionVo(ChatSession session, Long userId, Map<Long, Long> boxUnread) {
        Long peerId = Objects.equals(session.getUserAId(), userId) ? session.getUserBId() : session.getUserAId();
        SysUser peer = sysUserMapper.selectById(peerId);
        return new ChatSessionVO(
                session.getId(),
                peerId,
                peer == null ? "同学" : peer.getNickname(),
                peer == null ? null : FileUrls.of(peer.getAvatarUrl()),
                session.getLastContent(),
                session.getLastMessageAt(),
                unreadOf(session, userId, boxUnread),
                UserRestrictions.muted(peer),
                peer != null && UserStatus.BANNED.name().equals(peer.getStatus()),
                session.getLastSeqNo()
        );
    }

    /**
     * 该用户在某个会话上的未读数 —— <b>口径切换的唯一落点</b>（阶段 B-3）。
     *
     * <p>取值顺序（不可颠倒）：</p>
     * <ol>
     *   <li>{@code boxUnread} 里有这个会话 ⇒ 用 box 算出来的值
     *       （调用方已按 {@code kean.im.unread-source} 决定是否去查 box）；</li>
     *   <li>没有 ⇒ 回退到 kean 的 {@code chat_session.a_unread / b_unread}
     *       —— 这正是开关未打开、以及「box 查询失败」两种情况下的<b>同一条</b>路径，
     *       所以「切到 box 一半失败」不会让聊天页出现空洞或 500。</li>
     * </ol>
     * <p>⚠️ 注意缺失（键不存在）与 0 是<b>不同</b>的：box 明确算出 0 时 {@code boxUnread}
     * 里会有这个键，此时<b>不会</b>回退 —— 否则「box 说已读、kean 计数器还没清零」
     * 这个中间态会把角标又显示出来。</p>
     */
    private static int unreadOf(ChatSession session, Long userId, Map<Long, Long> boxUnread) {
        if (boxUnread != null) {
            Long fromBox = boxUnread.get(session.getId());
            if (fromBox != null) {
                return fromBox > Integer.MAX_VALUE ? Integer.MAX_VALUE : fromBox.intValue();
            }
        }
        Integer unread = Objects.equals(session.getUserAId(), userId) ? session.getAUnread() : session.getBUnread();
        return unread == null ? 0 : unread;
    }

    /**
     * 只查一个/一小批会话的 box 未读数（会话详情、开会话用）。
     *
     * <p>{@link ImUnreadQueryService#unreadCounts} 在开关为 {@code kean} 时直接返回空 Map，
     * <b>不产生任何 SQL</b>；失败时同样返回空 Map（并已自行 WARN）。</p>
     */
    private Map<Long, Long> boxUnreadOf(Long userId, List<ChatSession> sessions) {
        Map<Long, Long> counts = imUnreadQueryService.unreadCounts(userId, sessions);
        return counts == null ? Collections.emptyMap() : counts;
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
