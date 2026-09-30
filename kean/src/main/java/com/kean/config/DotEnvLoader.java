package com.kean.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 启动前加载仓库根目录的 .env，以及当前 Spring Profile 对应的 .env.{profile}。
 */
public final class DotEnvLoader {

    private DotEnvLoader() {
    }

    public static void load() {
        List<Path> roots = resolveRoots();
        for (Path root : roots) {
            apply(root.resolve(".env"), false);
        }
        String profile = resolveProfile();
        if (profile.isEmpty()) {
            return;
        }
        for (Path root : roots) {
            apply(root.resolve(".env." + profile), true);
        }
    }

    private static List<Path> resolveRoots() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Set<Path> roots = new LinkedHashSet<>();
        roots.add(cwd);
        roots.add(cwd.resolve("kean"));
        if (cwd.getParent() != null) {
            roots.add(cwd.getParent());
        }
        return new ArrayList<>(roots);
    }

    private static String resolveProfile() {
        String raw = firstNonBlank(
                System.getenv("SPRING_PROFILES_ACTIVE"),
                System.getProperty("SPRING_PROFILES_ACTIVE"),
                System.getProperty("spring.profiles.active"),
                "dev"
        );
        int comma = raw.indexOf(',');
        return comma < 0 ? raw.trim() : raw.substring(0, comma).trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static void apply(Path path, boolean override) {
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        try {
            for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                    continue;
                }
                int split = line.indexOf('=');
                String key = line.substring(0, split).trim();
                String value = line.substring(split + 1).trim();
                if (key.isEmpty() || value.isEmpty()) {
                    continue;
                }
                if (System.getenv(key) != null) {
                    continue;
                }
                if (!override && System.getProperty(key) != null) {
                    continue;
                }
                System.setProperty(key, value);
            }
        } catch (IOException ignored) {
            // 继续尝试下一个候选文件
        }
    }
}
