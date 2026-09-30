package com.kean.vo;

public record AdminUserStatusVO(
        long normal,
        long banned,
        long restricted,
        long forbidPublish,
        long forbidApply,
        long muted
) {
}
