package com.kean.vo;

import java.time.LocalDateTime;

public record AdminTimelineVO(
        LocalDateTime at,
        String event,
        String label
) {
}
