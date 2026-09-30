package com.kean.security;

import com.kean.config.JwtProperties;
import com.kean.utils.FileUrls;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

@Component
public class FileUrlSigner {

    /** 敏感目录：需要 URL 签名，且读取时还要校验归属关系。FileAccessGuard 复用同一份定义。 */
    public static final Set<String> SENSITIVE_FOLDERS = Set.of("report", "appeal", "chat", "fulfill");
    private static final long TTL_SECONDS = 2 * 60 * 60;

    private final byte[] secret;

    public FileUrlSigner(JwtProperties jwtProperties) {
        this.secret = (jwtProperties.getSecret() + "|file-url").getBytes(StandardCharsets.UTF_8);
    }

    @PostConstruct
    public void register() {
        FileUrls.setSigner(this);
    }

    public boolean isSensitive(String objectKey) {
        String folder = folderOf(objectKey);
        return folder != null && SENSITIVE_FOLDERS.contains(folder);
    }

    public String sign(String path, String objectKey) {
        if (!isSensitive(objectKey)) {
            return path;
        }
        long exp = Instant.now().getEpochSecond() + TTL_SECONDS;
        return path + "?exp=" + exp + "&sig=" + hmac(objectKey, exp);
    }

    public boolean verify(String objectKey, String expRaw, String sig) {
        if (!isSensitive(objectKey)) {
            return true;
        }
        if (!StringUtils.hasText(expRaw) || !StringUtils.hasText(sig)) {
            return false;
        }
        long exp;
        try {
            exp = Long.parseLong(expRaw.trim());
        } catch (NumberFormatException ex) {
            return false;
        }
        if (exp < Instant.now().getEpochSecond()) {
            return false;
        }
        String expected = hmac(objectKey, exp);
        byte[] left = expected.getBytes(StandardCharsets.UTF_8);
        byte[] right = sig.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return left.length == right.length && MessageDigest.isEqual(left, right);
    }

    private String hmac(String objectKey, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] raw = mac.doFinal((objectKey + ":" + exp).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception ex) {
            throw new IllegalStateException("文件签名失败", ex);
        }
    }

    private static String folderOf(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return null;
        }
        int slash = objectKey.indexOf('/');
        String folder = slash < 0 ? objectKey : objectKey.substring(0, slash);
        return folder.toLowerCase(Locale.ROOT);
    }
}
