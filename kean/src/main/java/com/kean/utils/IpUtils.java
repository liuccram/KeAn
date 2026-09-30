package com.kean.utils;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

public final class IpUtils {

    private static volatile List<String> trustedProxies = List.of();

    private IpUtils() {
    }

    public static void setTrustedProxies(List<String> proxies) {
        trustedProxies = proxies == null || proxies.isEmpty() ? List.of() : List.copyOf(new ArrayList<>(proxies));
    }

    public static String clientIp(HttpServletRequest request) {
        String remote = normalize(request.getRemoteAddr());
        if (!isTrustedProxy(remote)) {
            return remote;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            String first = forwarded.split(",")[0].trim();
            if (StringUtils.hasText(first)) {
                return normalize(first);
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return normalize(realIp.trim());
        }
        return remote;
    }

    static boolean isTrustedProxy(String ip) {
        if (!StringUtils.hasText(ip)) {
            return false;
        }
        for (String rule : trustedProxies) {
            if (matches(ip, rule)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String ip, String rule) {
        if (!StringUtils.hasText(rule)) {
            return false;
        }
        String pattern = rule.trim();
        if (pattern.contains("/")) {
            return ipv4InCidr(ip, pattern);
        }
        return ip.equalsIgnoreCase(pattern) || unwrapIpv4(ip).equals(pattern);
    }

    private static boolean ipv4InCidr(String ip, String cidr) {
        String[] parts = cidr.split("/", 2);
        if (parts.length != 2) {
            return false;
        }
        long network = ipv4ToLong(parts[0]);
        long address = ipv4ToLong(unwrapIpv4(ip));
        if (network < 0 || address < 0) {
            return false;
        }
        int prefix;
        try {
            prefix = Integer.parseInt(parts[1]);
        } catch (NumberFormatException ex) {
            return false;
        }
        if (prefix < 0 || prefix > 32) {
            return false;
        }
        long mask = prefix == 0 ? 0 : 0xFFFFFFFFL << (32 - prefix);
        return (address & mask) == (network & mask);
    }

    private static long ipv4ToLong(String ip) {
        String[] segs = ip.split("\\.");
        if (segs.length != 4) {
            return -1;
        }
        long value = 0;
        try {
            for (String seg : segs) {
                int n = Integer.parseInt(seg);
                if (n < 0 || n > 255) {
                    return -1;
                }
                value = (value << 8) | n;
            }
        } catch (NumberFormatException ex) {
            return -1;
        }
        return value;
    }

    private static String unwrapIpv4(String ip) {
        if (ip != null && ip.startsWith("::ffff:")) {
            return ip.substring(7);
        }
        return ip == null ? "" : ip;
    }

    private static String normalize(String ip) {
        if (!StringUtils.hasText(ip)) {
            return "";
        }
        String value = ip.trim();
        if (value.startsWith("::ffff:")) {
            return value.substring(7);
        }
        if ("0:0:0:0:0:0:0:1".equals(value)) {
            return "::1";
        }
        return value;
    }
}
