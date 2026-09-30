package com.kean.utils;

import com.kean.common.ErrorCode;
import com.kean.entity.SysUser;
import com.kean.exception.BizException;

import java.time.LocalDateTime;
import java.util.Set;

public final class UserRestrictions {

    public static final Set<Integer> DAYS = Set.of(1, 3, 7, 30);

    private UserRestrictions() {
    }

    public static boolean active(Integer flag, LocalDateTime until) {
        if (flag == null || flag != 1) {
            return false;
        }
        return until == null || until.isAfter(LocalDateTime.now());
    }

    public static boolean forbidPublish(SysUser user) {
        return user != null && active(user.getForbidPublish(), user.getForbidPublishUntil());
    }

    public static boolean forbidApply(SysUser user) {
        return user != null && active(user.getForbidApply(), user.getForbidApplyUntil());
    }

    public static boolean muted(SysUser user) {
        return user != null && active(user.getMuted(), user.getMutedUntil());
    }

    public static int flag(Integer value, LocalDateTime until) {
        return active(value, until) ? 1 : 0;
    }

    public static LocalDateTime untilOf(Integer days) {
        if (days == null || !DAYS.contains(days)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择限制天数：1、3、7 或 30 天");
        }
        return LocalDateTime.now().plusDays(days);
    }
}
