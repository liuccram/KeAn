package com.kean.service;

public interface PresenceService {

    void heartbeat(Long userId);

    void offline(Long userId);

    long onlineCount();

    boolean isOnline(Long userId);

    java.util.Set<Long> onlineUserIds();
}
