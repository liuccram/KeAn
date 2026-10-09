package com.kean.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kean.chat.ChatSessionHub;
import com.kean.chat.RealtimePublisher;
import com.kean.entity.Announcement;
import com.kean.entity.ChatSession;
import com.kean.entity.SysUser;
import com.kean.im.ImKickService;
import com.kean.mapper.AnnouncementMapper;
import com.kean.mapper.ChatSessionMapper;
import com.kean.security.SecurityUtils;
import com.kean.security.TokenRevokeService;
import com.kean.service.AccountBanService;
import com.kean.service.NotificationService;
import com.kean.service.PresenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class AccountBanServiceImpl implements AccountBanService {

    private static final Logger log = LoggerFactory.getLogger(AccountBanServiceImpl.class);

    static final String DEFAULT_BAN_MESSAGE = "你的账号因违规已被封禁，如有疑问请联系平台。";

    private final TokenRevokeService tokenRevokeService;
    private final AnnouncementMapper announcementMapper;
    private final NotificationService notificationService;
    private final PresenceService presenceService;
    private final ChatSessionHub chatSessionHub;
    private final ChatSessionMapper chatSessionMapper;
    private final RealtimePublisher realtimePublisher;
    private final ObjectMapper objectMapper;

    /**
     * box-im（Netty 8878）侧的封禁联动。只<b>新增</b>调用，不改本类原有的四步联动；
     * IM 未启用时它内部全部 no-op。
     */
    private final ImKickService imKickService;

    public AccountBanServiceImpl(
            TokenRevokeService tokenRevokeService,
            AnnouncementMapper announcementMapper,
            NotificationService notificationService,
            PresenceService presenceService,
            ChatSessionHub chatSessionHub,
            ChatSessionMapper chatSessionMapper,
            RealtimePublisher realtimePublisher,
            ObjectMapper objectMapper,
            ImKickService imKickService
    ) {
        this.tokenRevokeService = tokenRevokeService;
        this.announcementMapper = announcementMapper;
        this.notificationService = notificationService;
        this.presenceService = presenceService;
        this.chatSessionHub = chatSessionHub;
        this.chatSessionMapper = chatSessionMapper;
        this.realtimePublisher = realtimePublisher;
        this.objectMapper = objectMapper;
        this.imKickService = imKickService;
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
        denyIm(user.getId(), content);
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
        allowIm(userId);
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

    /**
     * 封禁时同步到 box-im（im-server 8878）：写封禁标记 + 尽力踢掉已建立的长连接。
     *
     * <p><b>只新增调用，不改上面已有的四步联动。</b>整段用 try/catch 兜住：
     * Redis 抖动、im-server 不可达、im-platform 未部署……任何情况都只 log.warn，
     * <b>绝不能让 IM 侧的失败影响 kean 的封禁本身</b>。IM 未启用时 {@code ImKickService}
     * 内部直接 no-op，连 Redis 都不会碰。</p>
     */
    private void denyIm(Long userId, String reason) {
        try {
            imKickService.deny(userId, reason);
        } catch (Exception ex) {
            log.warn("[IM 联动失败] 封禁用户时同步 box-im 失败（kean 封禁已生效，不受影响），userId={}：{}",
                    userId, ex.getMessage());
        }
    }

    /**
     * 解封时清掉 box-im 侧的封禁标记。同样只新增调用、同样吞掉所有异常。
     *
     * <p>被踢掉的 im-server 连接不会因此自动恢复，用户需重新获取 IM token 再连。</p>
     */
    private void allowIm(Long userId) {
        try {
            imKickService.allow(userId);
        } catch (Exception ex) {
            log.warn("[IM 联动失败] 解封用户时清理 box-im 状态失败（kean 解封已生效，不受影响），userId={}：{}",
                    userId, ex.getMessage());
        }
    }
}
