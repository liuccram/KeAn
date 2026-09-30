package com.kean.security;

import com.kean.common.ErrorCode;
import com.kean.enums.UserRole;
import com.kean.exception.BizException;

public final class AdminGuard {

    private AdminGuard() {
    }

    public static LoginUser require() {
        LoginUser user = SecurityUtils.currentUser();
        if (!UserRole.ADMIN.name().equals(user.role())) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        return user;
    }
}
