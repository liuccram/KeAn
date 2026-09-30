package com.kean.security;

import com.kean.common.ErrorCode;
import com.kean.exception.BizException;
import org.springframework.util.StringUtils;

public final class AdminPasswords {

    public static final String FORBIDDEN_DEFAULT = "ChangeMe_Admin_123";
    public static final int SEED_MIN_LENGTH = 12;

    private AdminPasswords() {
    }

    public static boolean isForbiddenDefault(String raw) {
        return FORBIDDEN_DEFAULT.equals(raw);
    }

    public static void assertSeedPassword(String raw) {
        if (!StringUtils.hasText(raw) || isForbiddenDefault(raw.trim())) {
            throw new IllegalStateException("拒绝使用默认管理员密码，请在 .env 或 .env.{profile} 设置不少于 "
                    + SEED_MIN_LENGTH + " 位的 ADMIN_PASSWORD");
        }
        if (raw.trim().length() < SEED_MIN_LENGTH) {
            throw new IllegalStateException("ADMIN_PASSWORD 至少 " + SEED_MIN_LENGTH + " 位");
        }
    }

    public static void rejectIfDefault(String raw) {
        if (isForbiddenDefault(raw == null ? "" : raw.trim())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能使用系统默认密码");
        }
    }
}
