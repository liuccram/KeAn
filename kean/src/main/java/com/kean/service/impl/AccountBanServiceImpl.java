package com.kean.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kean.chat.ChatSessionHub;
import com.kean.chat.RealtimePublisher;
import com.kean.entity.Announcement;
import com.kean.entity.ChatSession;
import com.kean.entity.SysUser;
import com.kean.mapper.AnnouncementMapper;
import com.kean.mapper.ChatSessionMapper;
import com.kean.security.SecurityUtils;
import com.kean.security.TokenRevokeService;
import com.kean.service.AccountBanService;
import com.kean.service.NotificationService;
import com.kean.service.PresenceService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class AccountBanServiceImpl implements AccountBanService {

    static final String DEFAULT_BAN_MESSAGE = "你的账号因违规已被封禁，如有疑问请联系平台。";

    private final TokenRevokeService tokenRevokeService;
    private final AnnouncementMapper announcementMapper;
    private final NotificationService notificationService;
    private final PresenceService presenceService;
    private final ChatSessionHub chatSessionHub;
    private final ChatSessionMapper chatSessionMapper;
    private final RealtimePublisher realtimePublisher;
    private final ObjectMapper objectMapper;

    public AccountBanServiceImpl(
            TokenRevokeService tokenRevokeService,
            AnnouncementMapper announcementMapper,
            NotificationService notificationService,
            PresenceService presenceService,
            ChatSessionHub chatSessionHub,
            ChatSessionMapper chatSessionMapper,
            RealtimePublisher realtimePublisher,
            ObjectMapper objectMapper
    ) {
        this.tokenRevokeService = tokenRevokeService;
        this.announcementMapper = announcementMapper;
        this.notificationService = notificationService;
        this.presenceService = presenceService;
        this.chatSessionHub = chatSessionHub;
        this.chatSessionMapper = chatSessionMapper;
        this.realtimePublisher = realtimePublisher;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onBanned(SysUser user, String remark) {
        if (user == null || user.getId() == null) {
            return;
        }
        String content = StringUtils.hasText(remark) ? remark.trim() : DEFAULT_BAN_MESSAGE;
        tokenRevokeService.revoke(user.getId());
        tokenRevokeService.markBanned(user.getId(), content);
        publishBanAnnouncement(user, content);
        notificationService.notifyUser(user.getId(), "SYSTEM", "账号已被封禁", content, "USER", user.getId());
        presenceService.offline(user.getId());
        pushBanned(user.getId(), content);
        notifyChatPeers(user.getId(), true);
    }

    @Override
    public void onUnbanned(Long userId) {
        tokenRevokeService.clearBanned(userId);
        if (userId == null) {
            return;
        }
        announcementMapper.update(null, new LambdaUpdateWrapper<Announcement>()
                .eq(Announcement::getTargetUserId, userId)
                .eq(Announcement::getScope, "USER")
                .eq(Announcement::getStatus, "PUBLISHED")
                .set(Announcement::getStatus, "OFFLINE"));
        notifyChatPeers(userId, false);
    }

    private void publishBanAnnouncement(SysUser user, String content) {
        Announcement announcement = new Announcement();
        announcement.setTitle("账号已被封禁");
        announcement.setContent(content);
        announcement.setStatus("PUBLISHED");
        announcement.setScope("USER");
        announcement.setTargetUserId(user.getId());
        announcement.setPublisherId(currentPublisherId());
        announcement.setPublishedAt(LocalDateTime.now());
        announcementMapper.insert(announcement);
    }

    private Long currentPublisherId() {
        var login = SecurityUtils.currentUserOrNull();
        return login == null ? 0L : login.userId();
    }

    private void pushBanned(Long userId, String content) {
        try {
            chatSessionHub.sendTo(userId, objectMapper.writeValueAsString(Map.of(
                    "type", "BANNED",
                    "title", "账号已被封禁",
                    "content", content
            )));
        } catch (Exception ignored) {
            // 无在线长连接时由心跳接口返回 40301
        }
    }

    private void notifyChatPeers(Long userId, boolean banned) {
        if (userId == null) {
            return;
        }
        List<ChatSession> sessions = chatSessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getUserAId, userId)
                .or()
                .eq(ChatSession::getUserBId, userId));
        for (ChatSession session : sessions) {
            Long peerId = Objects.equals(session.getUserAId(), userId) ? session.getUserBId() : session.getUserAId();
            realtimePublisher.send(peerId, Map.of(
                    "type", "NOTICE",
                    "noticeType", banned ? "PEER_BANNED" : "PEER_UNBANNED",
                    "bizType", "CHAT",
                    "bizId", session.getId() == null ? 0 : session.getId()
            ));
        }
    }
}
