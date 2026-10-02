package com.kean.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FieldCipher} 的纯单元测试：不连数据库、不启 Spring 上下文。
 *
 * <p>密钥通过包级构造器显式注入，因此结果不随运行环境的 {@code DATA_ENC_KEY} 变化。
 * 「未配置密钥」的分支用 {@code new FieldCipher(null)} 直接构造。</p>
 */
@DisplayName("字段加密 FieldCipher")
class FieldCipherTest {

    /** 32 字节 ASCII 密钥（这里用 Base64 表示，覆盖「Base64 密钥」这条路径）。 */
    private static final String KEY_BASE64 =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    /** 同一个 32 字节密钥的 Hex 表示，用于验证 Hex 密钥也能识别。 */
    private static final String KEY_HEX = "3031323334353637383961626364656630313233343536373839616263646566";

    /** 另一个合法密钥，用于验证「换密钥后解不开」。 */
    private static final String KEY_OTHER =
            Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8));

    private static final int IV_BYTES = 12;

    private final FieldCipher cipher = new FieldCipher(KEY_BASE64);

    // ---------- 加解密往返 ----------

    @Test
    @DisplayName("往返一致：中文、emoji、空串、超长文本、含特殊字符")
    void roundTrips() {
        String[] samples = {
                "同学你好，明天的课帮我代一下，谢谢！",
                "emoji 也要能过 🙂🚀👨‍👩‍👧‍👦",
                "",
                "很长的文本。".repeat(2000),
                "换行\n制表\t引号\"'反斜杠\\百分号%下划线_",
                "带 v1: 前缀但是出现在中间 v1:AAAA 的内容",
                "'; DROP TABLE chat_message; --"
        };

        for (String sample : samples) {
            String encrypted = cipher.encrypt(sample);
            assertThat(encrypted).as("密文必须带版本前缀").startsWith(FieldCipher.VERSION_PREFIX);
            assertThat(cipher.decrypt(encrypted)).as("往返必须还原原文").isEqualTo(sample);
        }
    }

    @Test
    @DisplayName("空串会被加密成密文，但读回来仍是空串")
    void emptyStringRoundTrips() {
        String encrypted = cipher.encrypt("");

        assertThat(encrypted).isNotEqualTo("").startsWith(FieldCipher.VERSION_PREFIX);
        assertThat(cipher.decrypt(encrypted)).isEmpty();
    }

    @Test
    @DisplayName("密文与明文不同，且真的不是明文")
    void cipherTextIsNotPlainText() {
        String plain = "看起来像密码的文本";
        assertThat(cipher.encrypt(plain)).isNotEqualTo(plain);
    }

    @Test
    @DisplayName("null 原样返回，不抛异常")
    void nullPassesThroughUnchanged() {
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
    }

    // ---------- 随机 IV ----------

    @Test
    @DisplayName("同一明文两次加密的密文不同（随机 IV），但都能解回原文")
    void samePlainTextEncryptsDifferentlyEachTime() {
        String plain = "同一句话";

        String first = cipher.encrypt(plain);
        String second = cipher.encrypt(plain);

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo(plain);
        assertThat(cipher.decrypt(second)).isEqualTo(plain);
    }

    // ---------- 存量明文兼容 ----------

    @Test
    @DisplayName("不以 v1: 开头的字符串解密时原样返回（存量明文兼容）")
    void legacyPlainTextIsReturnedAsIs() {
        String[] legacy = {
                "这是库里已有的旧明文消息",
                "",
                "   ",
                "v1",                       // 缺冒号
                "V1:AAA",                   // 大小写不符
                " v1:AAA",                  // 前缀前有空格
                "随便什么 admin 备注..."
        };

        for (String value : legacy) {
            assertThat(cipher.decrypt(value)).as("存量明文必须原样返回：%s", value).isEqualTo(value);
        }
    }

    // ---------- 认证与失败 ----------

    @Test
    @DisplayName("密文被篡改一个字节时解密抛异常（GCM 认证生效）")
    void tamperedCipherTextFailsAuthentication() {
        String plain = "不可被悄悄改动的正文";
        String encrypted = cipher.encrypt(plain);

        // 分别破坏 IV 之后的密文首字节、认证标签末字节、以及 IV 本身
        assertThatThrownBy(() -> cipher.decrypt(flipByte(encrypted, IV_BYTES)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(flipByte(encrypted, -1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(flipByte(encrypted, 0)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("用另一个密钥解不开（密钥不匹配同样被认证拦下）")
    void decryptWithWrongKeyFails() {
        String encrypted = cipher.encrypt("只有原密钥能解开");

        assertThatThrownBy(() -> new FieldCipher(KEY_OTHER).decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("结构损坏的 v1: 值抛异常，而不是返回乱码")
    void malformedCipherTextFails() {
        assertThatThrownBy(() -> cipher.decrypt("v1:")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt("v1:AAAA")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt("v1:这不是 base64")).isInstanceOf(IllegalStateException.class);
    }

    // ---------- 未配置密钥 ----------

    @Test
    @DisplayName("未配置 DATA_ENC_KEY 时明文透传且不抛异常")
    void withoutKeyEverythingPassesThrough() {
        for (FieldCipher plaintext : new FieldCipher[]{new FieldCipher(null), new FieldCipher(""), new FieldCipher("   ")}) {
            assertThat(plaintext.enabled()).isFalse();
            assertThat(plaintext.encrypt("明文进")).isEqualTo("明文进");
            assertThat(plaintext.decrypt("明文出")).isEqualTo("明文出");
            // 此时没有任何密钥，v1: 密文也只能原样返回，而不是抛异常
            assertThat(plaintext.decrypt("v1:AAA")).isEqualTo("v1:AAA");
            assertThat(plaintext.encrypt(null)).isNull();
        }
    }

    @Test
    @DisplayName("密钥长度不是 32 字节 / 无法解析时退化为明文透传（不阻断启动）")
    void invalidKeyFallsBackToPassThrough() {
        assertThat(new FieldCipher("短密钥").enabled()).isFalse();
        assertThat(new FieldCipher("aa").enabled()).isFalse();
        assertThat(new FieldCipher(Base64.getEncoder().encodeToString(new byte[16])).enabled()).isFalse();
        assertThat(new FieldCipher("!!!not-base64!!!").enabled()).isFalse();
    }

    // ---------- 密钥表示与全局实例 ----------

    @Test
    @DisplayName("Hex 与 Base64 两种密钥表示都能用，且互相等价")
    void hexAndBase64KeysAreEquivalent() {
        String plain = "两种密钥写法应当等价";
        String encrypted = new FieldCipher(KEY_HEX).encrypt(plain);

        assertThat(new FieldCipher(KEY_HEX).decrypt(encrypted)).isEqualTo(plain);
        assertThat(new FieldCipher(KEY_BASE64).decrypt(encrypted)).isEqualTo(plain);
    }

    @Test
    @DisplayName("进程级实例在任何配置下都保证往返一致，绝不抛异常")
    void globalInstanceRoundTripsUnderAnyConfiguration() {
        FieldCipher global = FieldCipher.global();
        String plain = "全局实例的往返一致性";

        // 无论 DATA_ENC_KEY 是否配置（配置了就走真加密，没配就是明文透传），这条不变式都成立
        assertThat(global.decrypt(global.encrypt(plain))).isEqualTo(plain);
        // 存量明文在任何配置下都必须可读
        assertThat(global.decrypt("旧明文没有任何前缀")).isEqualTo("旧明文没有任何前缀");
    }

    // ---------- 辅助方法 ----------

    /**
     * 翻转密文中某个字节后重新组装成 v1: 字符串。
     *
     * @param index 明文载荷内的下标；{@code -1} 表示最后一个字节（认证标签末尾）
     */
    private static String flipByte(String cipherText, int index) {
        byte[] payload = Base64.getDecoder().decode(cipherText.substring(FieldCipher.VERSION_PREFIX.length()));
        int at = index < 0 ? payload.length + index : index;
        payload[at] ^= 0x01;
        return FieldCipher.VERSION_PREFIX + Base64.getEncoder().encodeToString(payload);
    }
}
