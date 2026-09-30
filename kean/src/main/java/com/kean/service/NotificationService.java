package com.kean.service;

import com.kean.common.PageResult;
import com.kean.vo.NotificationVO;

public interface NotificationService {

    void notifyUser(Long userId, String type, String title, String content, String bizType, Long bizId);

    PageResult<NotificationVO> listMine(Long page, Long size, String scope);

    long unreadCount(String scope);

    void markRead(Long id);

    void markAllRead(String scope);
}
