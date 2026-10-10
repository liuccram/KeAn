package com.kean.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kean.chat.RealtimePublisher;
import com.kean.common.ErrorCode;
import com.kean.common.PageResult;
import com.kean.entity.Notification;
import com.kean.exception.BizException;
import com.kean.mapper.NotificationMapper;
import com.kean.security.SecurityUtils;
import com.kean.service.NotificationService;
import com.kean.vo.NotificationVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class NotificationServiceImpl implements NotificationService {

    private static final long DEFAULT_PAGE = 1L;
    private static final long DEFAULT_SIZE = 20L;
    private static final long MAX_SIZE = 50L;

    private final NotificationMapper notificationMapper;
    private final RealtimePublisher realtimePublisher;

    public NotificationServiceImpl(NotificationMapper notificationMapper, RealtimePublisher realtimePublisher) {
        this.notificationMapper = notificationMapper;
        this.realtimePublisher = realtimePublisher;
    }

    @Override
    public void notifyUser(Long userId, String type, String title, String content, String bizType, Long bizId) {
        // 历史签名保持不变：直接委托给带角色的重载并把角色置为 null（不标注收件角色）。
        // 所有不需要标注角色的调用点（系统通知 / 申请通知 / 举报通知…）因此一行都不用改，
        // 落库行为与 V36 之前逐字相同。
        notifyUser(userId, type, title, content, bizType, bizId, null);
    }

    @Override
    public void notifyUser(Long userId, String type, String title, String content, String bizType, Long bizId, String receiverRole) {
        if (userId == null) {
            return;
        }
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setBizType(bizType);
        notification.setBizId(bizId);
        // 收件角色：只有"同一事件同时发给两方"的写入点会传 PUBLISHER / APPLICANT，
        // 其余调用点传 null（与 V36 之前的历史行同形）。
        notification.setReceiverRole(receiverRole);
        notification.setReadFlag(0);
        notificationMapper.insert(notification);
        realtimePublisher.notice(userId, type, bizType, bizId);
    }

    @Override
    public PageResult<NotificationVO> listMine(Long page, Long size, String scope) {
        Long userId = SecurityUtils.currentUserId();
        long pageNo = page == null || page < 1 ? DEFAULT_PAGE : page;
        long pageSize = size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        Page<Notification> result = notificationMapper.selectPage(
                new Page<>(pageNo, pageSize),
                scopedQuery(userId, scope).orderByDesc(Notification::getCreatedAt)
        );
        return new PageResult<>(
                result.getRecords().stream().map(this::toVo).toList(),
                result.getTotal(),
                pageNo,
                pageSize
        );
    }

    @Override
    public long unreadCount(String scope) {
        Long userId = SecurityUtils.currentUserId();
        return notificationMapper.selectCount(scopedQuery(userId, scope).eq(Notification::getReadFlag, 0));
    }

    @Override
    @Transactional
    public void markRead(Long id) {
        Long userId = SecurityUtils.currentUserId();
        Notification notification = notificationMapper.selectById(id);
        if (notification == null || !Objects.equals(notification.getUserId(), userId)) {
            throw new BizException(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
        if (notification.getReadFlag() != null && notification.getReadFlag() == 1) {
            return;
        }
        notification.setReadFlag(1);
        notificationMapper.updateById(notification);
    }

    @Override
    @Transactional
    public void markAllRead(String scope) {
        Long userId = SecurityUtils.currentUserId();
        LambdaUpdateWrapper<Notification> wrapper = new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getReadFlag, 0)
                .and(w -> w.isNull(Notification::getBizType).or().ne(Notification::getBizType, "ANNOUNCEMENT"))
                .set(Notification::getReadFlag, 1);
        applyScope(wrapper, scope);
        notificationMapper.update(null, wrapper);
    }

    private LambdaQueryWrapper<Notification> scopedQuery(Long userId, String scope) {
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .and(w -> w.isNull(Notification::getBizType).or().ne(Notification::getBizType, "ANNOUNCEMENT"));
        applyScope(wrapper, scope);
        return wrapper;
    }

    private void applyScope(LambdaQueryWrapper<Notification> wrapper, String scope) {
        if ("SYSTEM".equalsIgnoreCase(scope)) {
            wrapper.eq(Notification::getType, "SYSTEM");
        } else if ("TASK".equalsIgnoreCase(scope)) {
            wrapper.and(w -> w.in(Notification::getType, "TASK", "APPLICATION")
                    .or().in(Notification::getBizType, "TASK", "REVIEW"));
        }
    }

    private void applyScope(LambdaUpdateWrapper<Notification> wrapper, String scope) {
        if ("SYSTEM".equalsIgnoreCase(scope)) {
            wrapper.eq(Notification::getType, "SYSTEM");
        } else if ("TASK".equalsIgnoreCase(scope)) {
            wrapper.and(w -> w.in(Notification::getType, "TASK", "APPLICATION")
                    .or().in(Notification::getBizType, "TASK", "REVIEW"));
        }
    }

    private NotificationVO toVo(Notification item) {
        return new NotificationVO(
                item.getId(),
                item.getType(),
                item.getTitle(),
                item.getContent(),
                item.getBizType(),
                item.getBizId(),
                item.getReadFlag(),
                item.getCreatedAt(),
                item.getReceiverRole()
        );
    }
}
