package com.kean.vo;

import java.time.LocalDateTime;

public record LoginDeviceVO(
        Long id,
        String deviceName,
        String ip,
        Boolean current,
        Integer loginCount,
        LocalDateTime lastSeenAt,
        LocalDateTime createdAt
) {
}
