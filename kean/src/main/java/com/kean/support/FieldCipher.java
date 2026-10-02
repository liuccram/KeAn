package com.kean.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 单字段的应用层加密：AES-256-GCM（认证加密）。
 *
 * <p>只负责「字符串进、字符串出」，不依赖 Spring 容器、不读取配置文件，因此可以在
 * MyBatis {@code TypeHandler}、定时任务或任何普通代码里直接使用。</p>
 *
 * <h2>密文格式</h2>
 * <pre>
 * v1: + Base64( iv(12 字节) ‖ ciphertext ‖ tag(16 字节) )
 * </pre>
 * <p>{@code iv} 由 {@link SecureRandom} 每次加密重新生成 —— 同一明文两次加密的密文<b>必然不同</b>，
 * 因此本类的加密结果不能用作查询条件（等值检索、排序、去重都会失效）。</p>
 *
 * <p>前缀 {@code v1:} 是<b>密钥/格式版本号</b>，为将来轮换密钥留路：轮换时新增 {@code v2:} 与
 * 对应的新密钥，解密端按前缀选择密钥，历史数据无需一次性重写。</p>
 *
 * <h2>密钥来源</h2>
 * <p>进程启动时读取环境变量 {@code DATA_ENC_KEY}，支持 Base64 或 Hex 表示的 32 字节密钥
 * （64 个十六进制字符按 Hex 解析，其余按 Base64 解析）。</p>
 *
 * <h2>未配置密钥时的行为</h2>
 * <p><b>不抛异常、不阻止启动</b>，而是退化为<b>明文透传</b>：{@link #encrypt(String)} 原样返回，
 * {@link #decrypt(String)} 原样返回；同时打一条显眼的 WARN 说明「当前没有加密」。
 * 这样上线不会因为漏配环境变量而挂掉，配上了就自动加密。</p>
 *
 * <h2>存量兼容</h2>
 * <p>解密时若字符串不以 {@code v1:} 开头，就当成明文原样返回 —— 库里已有的旧明文记录
 * <b>不需要任何数据迁移</b>就能照常读取，并会在它下一次被写入时自动变成密文。</p>
 */
public final class FieldCipher {

    /** 密文格式 / 密钥版本前缀。解密时凭它区分「已加密」与「存量明文」。 */
    public static final String VERSION_PREFIX = "v1:";

    private static final Logger log = LoggerFactory.getLogger(FieldCipher.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";

    /** GCM 推荐的 IV 长度：12 字节。不要自己拼 IV，由 Cipher 与 GCMParameterSpec 管理。 */
    private static final int IV_BYTES = 12;

    /** GCM 认证标签长度：16 字节（128 位）。它是密文的一部分，用来检测篡改。 */
    private static final int TAG_BYTES = 16;
    private static final int TAG_BITS = TAG_BYTES * 8;

    /** AES-256 要求 32 字节密钥。 */
    private static final int KEY_BYTES = 32;

    /** 密钥所在的环境变量名。 */
    private static final String KEY_ENV = "DATA_ENC_KEY";

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 进程级默认实例。静态初始化即读取环境变量，因此密钥缺失的 WARN 会在应用启动
     * （MyBatis 解析实体上的 typeHandler、即构建 SqlSessionFactory 时）打出来。
     */
    private static final FieldCipher GLOBAL = new FieldCipher(System.getenv(KEY_ENV));

    /** {@code null} 表示「未启用加密」，此时所有操作都是明文透传。 */
    private final SecretKeySpec key;

    /**
     * @param rawKey Base64 或 Hex 表示的 32 字节密钥；为 {@code null}/空白/无法解析时退化为明文透传
     */
    FieldCipher(String rawKey) {
        this.key = parseKey(rawKey);
    }

    /** 进程级默认实例，密钥取自环境变量 {@code DATA_ENC_KEY}。 */
    public static FieldCipher global() {
        return GLOBAL;
    }

    /**
     * 当前实例是否真的会加密。
     *
     * <p>仅用于启动自检与排查：为 {@code false} 时说明密钥未配置或不可用，字段是明文落库的。</p>
     */
    public boolean enabled() {
        return key != null;
    }

    /**
     * 加密一个字段值。
     *
     * <p>{@code null} 与「未配置密钥」都原样返回。</p>
     *
     * @return {@code v1:} 前缀的密文；未启用加密时为入参本身
     */
    public String encrypt(String plain) {
        if (plain == null || key == null) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            // doFinal 的输出已经是 ciphertext ‖ tag，不需要（也不应该）自己拼接认证标签。
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] payload = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(sealed, 0, payload, iv.length, sealed.length);

            return VERSION_PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("字段加密失败", ex);
        }
    }

    /**
     * 解密一个字段值。
     *
     * <p><b>存量兼容（关键）</b>：不以 {@code v1:} 开头的字符串一律当成明文原样返回。
     * 库里已有的旧明文记录因此不需要任何数据迁移就能照常读取；旧数据会在它下一次被写入时
     * 自动变成密文。{@code null}、以及「未配置密钥」（此时无法解密任何东西）同样原样返回。</p>
     *
     * @throws IllegalStateException 密文被篡改（GCM 认证失败）或与当前密钥不匹配时抛出，绝不返回乱码
     */
    public String decrypt(String stored) {
        // 存量兼容：非 v1: 前缀 = 旧明文，直接原样返回，不做迁移。
        if (stored == null || key == null || !stored.startsWith(VERSION_PREFIX)) {
            return stored;
        }
        try {
            byte[] payload = Base64.getDecoder().decode(stored.substring(VERSION_PREFIX.length()));
            // 至少要装得下 IV 与认证标签；空串的合法密文正好是 28 字节。
            if (payload.length < IV_BYTES + TAG_BYTES) {
                throw new IllegalArgumentException("密文长度不足");
            }

            byte[] iv = Arrays.copyOfRange(payload, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);

            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            // 认证失败意味着密文被改动或密钥不匹配。必须显式失败：
            // 若在这里「宽容地」返回原文，攻击者就能靠篡改让数据变形而不被发现。
            throw new IllegalStateException("字段解密失败：密文可能已被篡改，或 DATA_ENC_KEY 与加密时不一致", ex);
        }
    }

    private static SecretKeySpec parseKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            log.warn("""
                    
                    ============================================================
                    [字段加密未启用] 环境变量 {} 未配置，敏感字段将以【明文】写入数据库。
                    如需启用 AES-256-GCM 加密，请配置 Base64 或 Hex 表示的 32 字节密钥：
                      DATA_ENC_KEY=<Base64 或 Hex 的 32 字节密钥>
                    配置后重启即对新写入的数据生效；旧明文数据无需迁移，仍可正常读取。
                    ============================================================""", KEY_ENV);
            return null;
        }

        byte[] bytes;
        try {
            bytes = decodeKey(rawKey.trim());
        } catch (RuntimeException ex) {
            log.warn("[字段加密未启用] 环境变量 {} 存在但无法解析为 Base64/Hex，敏感字段将以【明文】写入数据库：{}",
                    KEY_ENV, ex.getMessage());
            return null;
        }
        if (bytes.length != KEY_BYTES) {
            log.warn("[字段加密未启用] 环境变量 {} 解析后为 {} 字节，AES-256 需要 {} 字节；敏感字段将以【明文】写入数据库。",
                    KEY_ENV, bytes.length, KEY_BYTES);
            return null;
        }
        return new SecretKeySpec(bytes, KEY_ALGORITHM);
    }

    /**
     * 64 个十六进制字符按 Hex 解析，其余按 Base64 解析。
     *
     * <p>两者都是「32 字节密钥」的常见写法，自动识别可以让运维不必再记一个前缀。
     * 理论歧义：恰好由 64 个 Hex 字符组成的 Base64 串会被当成 Hex —— 实际不会这么写密钥。</p>
     */
    private static byte[] decodeKey(String value) {
        if (isHexKey(value)) {
            return hexToBytes(value);
        }
        return Base64.getDecoder().decode(value);
    }

    private static boolean isHexKey(String value) {
        if (value.length() != KEY_BYTES * 2) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] hexToBytes(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }
}
