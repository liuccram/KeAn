package com.kean.vo;

public record AdminUserSummaryVO(
        long total,
        long male,
        long female,
        long banned
) {
}
