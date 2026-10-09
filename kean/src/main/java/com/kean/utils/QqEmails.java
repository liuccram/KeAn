package com.kean.utils;

import com.kean.common.ErrorCode;
import com.kean.exception.BizException;
import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

public final class QqEmails {

    /**
     * 邮箱不合法时的统一提示（与前端 uni-kean/src/utils/qqEmail.ts 的 QQ_EMAIL_HINT 一致）。
     * 必须是**完整邮箱**：只填 QQ 号（12345678）、没有 @、非 qq.com 域名都不通过。
     */
    public static final String INVALID_MESSAGE = "请填写完整 QQ 邮箱，例如 12345678@qq.com";

    /** 完整 QQ 邮箱：QQ 号（5-11 位、不以 0 开头）+ @qq.com。 */
    public static final String REQUIRED_PATTERN = "^[1-9]\\d{4,10}@[Qq][Qq]\\.[Cc][Oo][Mm]$";

    /** 完整 QQ 邮箱或空串（部分发码场景允许不带邮箱，例如已登录用户改密码由服务端取自己的邮箱）。 */
    public static final String OPTIONAL_PATTERN = "^$|" + REQUIRED_PATTERN;

    private static final Pattern QQ_MAIL = Pattern.compile("^[1-9]\\d{4,10}@qq\\.com$", Pattern.CASE_INSENSITIVE);

    private QqEmails() {
    }

    /**
     * 规范化并校验 QQ 邮箱：只接受完整邮箱（12345678@qq.com），只填 QQ 号一律拒绝。
     * 服务端是最后一道关口：前端校验被绕过（直接打接口）时由这里兜底。
     */
    public static String normalize(String raw) {
        if (StringUtils.hasText(raw)) {
            String value = raw.trim().toLowerCase().replace(" ", "");
            if (QQ_MAIL.matcher(value).matches()) {
                return value;
            }
        }
        throw new BizException(ErrorCode.BAD_REQUEST, INVALID_MESSAGE);
    }
}
