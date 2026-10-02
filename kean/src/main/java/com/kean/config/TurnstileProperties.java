package com.kean.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.util.Set;
import java.util.regex.Pattern;

@Getter
@Setter
@ConfigurationProperties(prefix = "kean.turnstile")
public class TurnstileProperties {

    /**
     * 为 true 时登录必须通过 Cloudflare Turnstile；密钥未配则拒绝登录。
     */
    private boolean enabled = true;

    private String siteKey = "";

    private String secret = "";

    private String verifyUrl = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    public boolean ready() {
        return enabled && StringUtils.hasText(siteKey) && StringUtils.hasText(secret);
    }

    /**
     * siteKey 或 secret 是否为 Cloudflare 官方测试密钥。
     *
     * <p>测试密钥等于没有防护：测试 secret 只接受官方公开的 dummy token（{@code XXXX.DUMMY.TOKEN.XXXX}），
     * 知道这个公开值的人可以直接构造请求绕过；反过来真实用户拿着真实 token 却会被拒。
     * 所以命中测试密钥按"服务端配置错误"处理，并且不再去请求 Cloudflare。
     *
     * <p>这不会影响本地联调：联调的正规开关是 {@code TURNSTILE_ENABLED=false}（{@link #isEnabled()}），
     * 那条路径照旧直接跳过校验，不是 bug。故意填测试密钥还当"验证通过"才是真正危险的。
     */
    public boolean usesTestKey() {
        return isTestKey(siteKey) || isTestKey(secret);
    }

    private static boolean isTestKey(String value) {
        return StringUtils.hasText(value)
                && (TEST_KEYS.contains(value) || TEST_KEY_PATTERN.matcher(value).find());
    }

    /** Cloudflare 官方测试密钥（sitekey 与 secret 两侧，含不可见 widget 变体）。 */
    private static final Set<String> TEST_KEYS = Set.of(
            "1x00000000000000000000AA",
            "2x00000000000000000000AB",
            "1x00000000000000000000BB",
            "2x00000000000000000000BB",
            "3x00000000000000000000FF",
            "1x0000000000000000000000000000000AA",
            "2x0000000000000000000000000000000AA",
            "3x0000000000000000000000000000000AA"
    );

    /**
     * 兜底前缀：官方测试密钥都是 {@code 1x/2x/3x} 后跟一长串 0（至少 20 个）。
     * 真实密钥固定以 {@code 0x} 开头（约 23 位 sitekey / 32 位 secret），永远命中不了这个前缀，
     * 因此不会误伤；这样即使 Cloudflare 以后新增同族测试密钥也能覆盖。
     */
    private static final Pattern TEST_KEY_PATTERN = Pattern.compile("^[123]x0{20}");
}
