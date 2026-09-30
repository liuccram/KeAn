package com.kean.utils;

import com.kean.security.FileUrlSigner;
import org.springframework.util.StringUtils;

public final class FileUrls {

    public static final String PREFIX = "/api/files/";

    private static volatile FileUrlSigner signer;

    private FileUrls() {
    }

    public static void setSigner(FileUrlSigner fileUrlSigner) {
        signer = fileUrlSigner;
    }

    public static String of(String objectKey) {
        String key = objectKey(objectKey);
        if (!StringUtils.hasText(key) || !safeKey(key)) {
            return null;
        }
        String path = PREFIX + key;
        FileUrlSigner current = signer;
        return current == null ? path : current.sign(path, key);
    }

    public static String objectKey(String stored) {
        if (!StringUtils.hasText(stored)) {
            return null;
        }
        String value = stored.trim();
        int hash = value.indexOf('#');
        if (hash >= 0) {
            value = value.substring(0, hash);
        }
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        if (value.startsWith(PREFIX)) {
            return value.substring(PREFIX.length());
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            int idx = value.indexOf(PREFIX);
            return idx >= 0 ? value.substring(idx + PREFIX.length()) : value;
        }
        return value.replaceFirst("^/+", "");
    }

    public static boolean safeKey(String key) {
        if (!StringUtils.hasText(key)) {
            return false;
        }
        if (key.contains("..") || key.contains("\\") || key.startsWith("/") || key.contains(":")) {
            return false;
        }
        return key.indexOf('\0') < 0;
    }
}
