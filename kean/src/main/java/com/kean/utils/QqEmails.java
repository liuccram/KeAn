package com.kean.utils;

import com.kean.common.ErrorCode;
import com.kean.exception.BizException;
import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

public final class QqEmails {

    /** 允许只填 QQ 号，或带 @qq.com。 */
    public static final String REQUIRED_PATTERN = "^[1-9]\\d{4,10}(?:@[Qq][Qq]\\.[Cc][Oo][Mm])?$";

    public static final String OPTIONAL_PATTERN = "^$|^[1-9]\\d{4,10}(?:@[Qq][Qq]\\.[Cc][Oo][Mm])?$";

    private static final Pattern QQ_NO = Pattern.compile("^[1-9]\\d{4,10}$");
    private static final Pattern QQ_MAIL = Pattern.compile("^[1-9]\\d{4,10}@qq\\.com$", Pattern.CASE_INSENSITIVE);

    private QqEmails() {
    }

    public static String normalize(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写 QQ 号");
        }
        String value = raw.trim().toLowerCase().replace(" ", "");
        if (QQ_NO.matcher(value).matches()) {
            return value + "@qq.com";
        }
        if (QQ_MAIL.matcher(value).matches()) {
            return value;
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "请填写 5-11 位 QQ 号");
    }
}
