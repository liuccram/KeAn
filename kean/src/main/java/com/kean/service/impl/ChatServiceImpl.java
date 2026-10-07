package com.kean.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kean.chat.ChatWebSocketHandler;
import com.kean.common.ErrorCode;
import com.kean.common.PageResult;
import com.kean.entity.ChatMessage;
import com.kean.entity.ChatSession;
import com.kean.entity.SysUser;
import com.kean.enums.UserRole;
import com.kean.enums.UserStatus;
import com.kean.exception.BizException;
import com.kean.mapper.CampusMapper;
import com.kean.mapper.ChatMessageMapper;
import com.kean.mapper.ChatSessionMapper;
import com.kean.mapper.SysUserMapper;
import com.kean.security.SecurityUtils;
import com.kean.service.BlacklistService;
import com.kean.service.ChatService;
import com.kean.utils.CampusNames;
import com.kean.utils.FileUrls;
import com.kean.utils.UserRestrictions;
import com.kean.vo.ChatMessageVO;
import com.kean.vo.ChatPeerVO;
import com.kean.vo.ChatSessionVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ChatServiceImpl implements ChatService {

    private static final long DEFAULT_PAGE = 1L;
    private static final long DEFAULT_SIZE = 20L;
    private static final long MAX_SIZE = 50L;
    private static final int PEER_LIMIT = 30;

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final SysUserMapper sysUserMapper;
    private final CampusMapper campusMapper;
    private final ChatWebSocketHandler chatWebSocketHandler;
    private final BlacklistService blacklistService;

    public ChatServiceImpl(
            ChatSessionMapper chatSessionMapper,
            ChatMessageMapper chatMessageMapper,
            SysUserMapper sysUserMapper,
            CampusMapper campusMapper,
            ChatWebSocketHandler chatWebSocketHandler,
            BlacklistService blacklistService
    ) {
        this.chatSessionMapper = chatSessionMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.sysUserMapper = sysUserMapper;
        this.campusMapper = campusMapper;
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.blacklistService = blacklistService;
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

    @Override
    public PageResult<ChatMessageVO> messages(Long sessionId, Long page, Long size) {
        Long userId = SecurityUtils.currentUserId();
        requireOwnedSession(sessionId, userId);
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

    @Override
    @Transactional
    public ChatMessageVO send(Long sessionId, String msgType, String content) {
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
        ChatSession session = requireOwnedSession(sessionId, userId);
        Long peerId = Objects.equals(session.getUserAId(), userId) ? session.getUserBId() : session.getUserAId();
        SysUser peer = sysUserMapper.selectById(peerId);
        if (peer != null && UserStatus.BANNED.name().equals(peer.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "对方已被封禁");
        }
        blacklistService.assertCanInteract(userId, peerId);
        ChatMessage message = new ChatMessage();
        message.setSessionId(session.getId());
        message.setSenderId(userId);
        message.setMsgType(type);
        message.setContent(text);
        chatMessageMapper.insert(message);

        boolean senderIsA = Objects.equals(session.getUserAId(), userId);
        session.setLastMessageAt(LocalDateTime.now());
        String preview = "IMAGE".equals(type) ? "[图片]" : (text.length() > 200 ? text.substring(0, 200) : text);
        session.setLastContent(preview);
        if (senderIsA) {
            session.setBUnread((session.getBUnread() == null ? 0 : session.getBUnread()) + 1);
        } else {
            session.setAUnread((session.getAUnread() == null ? 0 : session.getAUnread()) + 1);
        }
        chatSessionMapper.updateById(session);

        ChatMessageVO vo = toMessageVo(message, userId);
        chatWebSocketHandler.pushMessage(peerId, toMessageVo(message, peerId));
        return vo;
    }

    @Override
    @Transactional
    public void markRead(Long sessionId) {
        Long userId = SecurityUtils.currentUserId();
        ChatSession session = requireOwnedSession(sessionId, userId);
        if (Objects.equals(session.getUserAId(), userId)) {
            session.setAUnread(0);
        } else {
            session.setBUnread(0);
        }
        chatSessionMapper.updateById(session);
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

    @Override
    public List<ChatPeerVO> listPeers(String keyword) {
        SysUser me = requireUser(SecurityUtils.currentUserId());
        // 可发起私信的人不再限定本校：这里只按角色/封禁/黑名单过滤。
        // 说明：校区可选后 me.getSchoolId() 可能为空，原实现会直接返回空列表，现已去掉该分支。
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRole, UserRole.USER.name())
                .ne(SysUser::getId, me.getId())
                .ne(SysUser::getStatus, UserStatus.BANNED.name())
                .orderByAsc(SysUser::getNickname)
                .last("LIMIT " + PEER_LIMIT);
        if (StringUtils.hasText(keyword)) {
            String like = "%" + keyword.trim() + "%";
            wrapper.and(w -> w.like(SysUser::getNickname, like).or().like(SysUser::getUsername, like));
        }
        List<SysUser> users = sysUserMapper.selectList(wrapper);
        Set<Long> blocked = blacklistService.relatedUserIds(me.getId());
        Set<Long> chatting = chattingPeerIds(me.getId());
        Set<Long> campusIds = users.stream().map(SysUser::getCampusId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> campusNames = new HashMap<>();
        if (!campusIds.isEmpty()) {
            campusMapper.selectByIds(campusIds).forEach(campus -> campusNames.put(campus.getId(), campus.getName()));
        }
        return users.stream()
                .filter(user -> !blocked.contains(user.getId()))
                .filter(user -> user.getPrivateAccount() == null || user.getPrivateAccount() != 1 || chatting.contains(user.getId()))
                .map(user -> new ChatPeerVO(
                        user.getId(),
                        user.getNickname(),
                        FileUrls.of(user.getAvatarUrl()),
                        // 校区改为手输文本：优先文本，旧数据回退到 campus_id 关联出的旧校区名
                        CampusNames.display(user.getCampusText(),
                                user.getCampusId() == null ? null : campusNames.get(user.getCampusId()))
                ))
                .toList();
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

    private Set<Long> chattingPeerIds(Long userId) {
        List<ChatSession> sessions = chatSessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                .and(w -> w.eq(ChatSession::getUserAId, userId).or().eq(ChatSession::getUserBId, userId)));
        return sessions.stream()
                .map(session -> Objects.equals(session.getUserAId(), userId) ? session.getUserBId() : session.getUserAId())
                .collect(Collectors.toSet());
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
                peer != null && UserStatus.BANNED.name().equals(peer.getStatus())
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
                Objects.equals(message.getSenderId(), viewerId)
        );
    }
}
