package com.kean.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 签发 / 校验 <b>box-im 兼容</b>的 JWT（阶段 D2-(c)）。
 *
 * <p>本类只做“让 kean 能自己签发 box-im 认得的 token”这一件事，
 * <b>不参与</b>课安自身的登录鉴权：现有 {@code JwtService} / {@code JwtAuthFilter} /
 * {@code SecurityConfig} / {@code chat.vue} / {@code utils/ws.ts} 等链路一行未改。
 * 两套 token 完全独立：{@code kean.jwt.secret} 负责课安，本类负责 im-server。</p>
 *
 * <h2>为什么必须逐字节对齐 box-im</h2>
 * <p>im-server 的 {@code LoginProcessor} 收到 {@code {cmd:0,data:{accessToken,devId}}} 后：
 * <ol>
 *   <li>{@code JwtUtil.checkSign(accessToken, ${jwt.accessToken.secret})} ——
 *       HMAC256 密钥必须与 im-server 的 {@code jwt.accessToken.secret} <b>完全相同</b>；</li>
 *   <li>{@code JSON.parseObject(JwtUtil.getInfo(token), IMSessionInfo.class)} ——
 *       自定义声明 {@code info} 必须是 {@code {"userId":..,"terminal":..}} 的 JSON
 *       （{@code getInfo} 读的是名为 {@code info} 的 claim）。</li>
 * </ol>
 * 任一处不匹配，im-server 会直接 {@code ctx.channel().close()} 且只在日志里留一行 WARN，
 * 客户端表现为“连上就断”。所以下面 {@link #sign} 的 claim 布局是<b>刻意写死</b>的，
 * 不要按课安自己的 token 结构去“顺手统一”。</p>
 *
 * <h2>密钥来源与未配置时的行为</h2>
 * <p>密钥取自环境变量 {@code IM_JWT_SECRET}（也可由仓库根 {@code .env} 提供 ——
 * {@code DotEnvLoader} 会把它塞进系统属性，Spring 的 {@code @Value} 对系统属性与环境变量同样生效）。
 * 该值必须与 im-server 的 {@code jwt.accessToken.secret} 一致。</p>
 *
 * <p><b>与本项目 {@code DATA_ENC_KEY} 的既有风格保持一致：未配置不抛异常、不阻止启动</b>，
 * 只在启动时 WARN 一次，随后 {@link #enabled()} 恒为 {@code false}、
 * {@code GET /api/im/token} 返回 {@code enabled=false}。这样漏配环境变量不会把整个后端带崩 ——
 * 代价是 IM 通道不可用，而课安原有功能完全不受影响。</p>
 */
@Service
public class ImTokenService {

    private static final Logger log = LoggerFactory.getLogger(ImTokenService.class);

    /** 与 im-server 的 accessToken 密钥同源的环境变量名。 */
    private static final String ACCESS_SECRET_ENV = "IM_JWT_SECRET";

    /**
     * refreshToken 密钥（可选）。box-im 里 accessToken 与 refreshToken 用的是<b>两个不同</b>的
     * secret（{@code MIIBIjANBgkq} / {@code IKDiqVmn0VFU}），因此这里也支持分开配置。
     * 未配置时由 accessToken 密钥派生（见 {@link #deriveRefreshSecret}），仍然满足
     * “access 只用于接 im-server、refresh 只用于我们自己换票”的隔离要求。
     */
    private static final String REFRESH_SECRET_ENV = "IM_JWT_REFRESH_SECRET";

    /** box-im im-platform 的 {@code jwt.accessToken.expireIn}：1800s。 */
    private static final long DEFAULT_ACCESS_EXPIRE_SECONDS = 1800L;

    /** box-im im-platform 的 {@code jwt.refreshToken.expireIn}：604800s（7 天）。 */
    private static final long DEFAULT_REFRESH_EXPIRE_SECONDS = 604800L;

    /** `info` 声明里终端字段的默认值：box-im {@code IMTerminalType.APP == 1}。 */
    private static final int DEFAULT_TERMINAL = 1;

    private final ObjectMapper objectMapper;

    /** {@code null} 表示「IM 未启用」，此时所有签发/校验方法都拒绝工作。 */
    private final SecretKey accessKey;

    private final SecretKey refreshKey;

    private final long accessExpireSeconds;
    private final long refreshExpireSeconds;

    public ImTokenService(
            ObjectMapper objectMapper,
            @Value("${" + ACCESS_SECRET_ENV + ":}") String accessSecret,
            @Value("${" + REFRESH_SECRET_ENV + ":}") String refreshSecret,
            @Value("${kean.im.access-expire-seconds:" + DEFAULT_ACCESS_EXPIRE_SECONDS + "}") long accessExpireSeconds,
            @Value("${kean.im.refresh-expire-seconds:" + DEFAULT_REFRESH_EXPIRE_SECONDS + "}") long refreshExpireSeconds
    ) {
        this.objectMapper = objectMapper;
        this.accessExpireSeconds = accessExpireSeconds;
        this.refreshExpireSeconds = refreshExpireSeconds;

        if (!StringUtils.hasText(accessSecret)) {
            log.warn("""
                    
                    ============================================================
                    [IM 未启用] 环境变量 {} 未配置，box-im 兼容 token 无法签发。
                    GET /api/im/token 将返回 enabled=false，uni-kean 的 IM 通道保持关闭。
                    如需启用，请配置与 im-server 的 jwt.accessToken.secret 完全一致的密钥：
                      IM_JWT_SECRET=<与 im-server 相同的密钥>
                    （可选）IM_JWT_REFRESH_SECRET=<refreshToken 密钥；不配则由上式派生>
                    课安现有的 HTTP / WebSocket 聊天链路不受任何影响。
                    ============================================================""", ACCESS_SECRET_ENV);
            this.accessKey = null;
            this.refreshKey = null;
            return;
        }

        this.accessKey = Keys.hmacShaKeyFor(accessSecret.getBytes(StandardCharsets.UTF_8));
        this.refreshKey = StringUtils.hasText(refreshSecret)
                ? Keys.hmacShaKeyFor(refreshSecret.getBytes(StandardCharsets.UTF_8))
                : deriveRefreshSecret(accessSecret);
    }

    /**
     * IM 通道是否可用（即 {@code IM_JWT_SECRET} 是否已配置且可用）。
     *
     * <p>控制器据此决定返回 {@code enabled=true} 还是一份空壳。</p>
     */
    public boolean enabled() {
        return accessKey != null;
    }

    /**
     * 签发一对 box-im 兼容 token。
     *
     * @param userId   课安用户 id；会同时写入 {@code aud}（box-im 把 userId 放在 aud 里，
     *                 {@code JwtUtil.getUserId} 读的就是 {@code JWT.decode(token).getAudience().get(0)}）
     *                 与 {@code info.userId}
     * @param terminal box-im {@code IMTerminalType} 终端码（WEB=0 / APP=1 / PC=2）；非法值回落到 APP
     * @return 双 token + accessToken 的绝对过期时间（epoch 毫秒）
     * @throws IllegalStateException 未配置 {@code IM_JWT_SECRET} 时抛出
     */
    public ImTokenPair issue(Long userId, Integer terminal) {
        if (!enabled()) {
            throw new IllegalStateException("IM 未启用：请先配置环境变量 " + ACCESS_SECRET_ENV);
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        String info = sessionInfoJson(userId, terminal);

        Instant now = Instant.now();
        Instant accessExpireAt = now.plusSeconds(accessExpireSeconds);
        String accessToken = sign(userId, info, accessExpireAt, accessKey);
        String refreshToken = sign(userId, info, now.plusSeconds(refreshExpireSeconds), refreshKey);
        return new ImTokenPair(accessToken, refreshToken, accessExpireAt.toEpochMilli());
    }

    /**
     * 本地校验一个 accessToken 是否由本服务签发、且未过期。
     *
     * <p>注意：im-server <b>不调用</b>本方法，它用自己那份 secret 独立校验。
     * 这里提供校验纯粹是为了让「密钥配错了」能在第 1 步就被发现，
     * 而不是等到客户端连上 im-server 又被踢下来才去翻日志。</p>
     *
     * @return 解析出的声明；token 非法 / 过期 / 未启用 IM 时返回 {@code null}
     */
    public Claims verifyAccessToken(String token) {
        if (accessKey == null || !StringUtils.hasText(token)) {
            return null;
        }
        try {
            return Jwts.parser()
                    .verifyWith(accessKey)
                    .build()
                    .parseSignedClaims(token.trim())
                    .getPayload();
        } catch (JwtException | IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * 拼出 box-im 认得的 {@code info} 声明。
     *
     * <p>box-im 侧用 fastjson 序列化 {@code UserSession}（它继承 {@code IMSessionInfo}），
     * 产出的 key 是小驼峰 {@code userId} / {@code terminal}，im-server 再用 fastjson
     * 反序列化回 {@code IMSessionInfo}。这里用 Jackson 产出同样的小驼峰结构，
     * 两个库对该结构互相可解。多出来的字段（box-im 的 userSession 里还有 userName / nickName）
     * 不是必需的，im-server 只会读 userId 与 terminal。</p>
     */
    private String sessionInfoJson(Long userId, Integer terminal) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("userId", userId);
        info.put("terminal", validTerminal(terminal));
        try {
            return objectMapper.writeValueAsString(info);
        } catch (Exception ex) {
            throw new IllegalStateException("IM session 序列化失败", ex);
        }
    }

    /** box-im {@code IMTerminalType}：WEB=0 / APP=1 / PC=2；其余（含 null、UNKNOW=-1）一律按 APP 处理。 */
    private static int validTerminal(Integer terminal) {
        if (terminal == null) {
            return DEFAULT_TERMINAL;
        }
        return switch (terminal) {
            case 0, 1, 2 -> terminal;
            default -> DEFAULT_TERMINAL;
        };
    }

    /**
     * 严格按 box-im {@code JwtUtil.sign} 的结构签发，手工构造 Claims 负载顺序：
     * {@code aud}(userId) → {@code info} → {@code exp}。
     */
    private static String sign(Long userId, String info, Instant expireAt, SecretKey key) {
        return Jwts.builder()
                .audience().add(String.valueOf(userId)).and()
                .claim("info", info)
                .expiration(Date.from(expireAt))
                .signWith(key)
                .compact();
    }

    /**
     * 由 accessToken 密钥派生 refreshToken 密钥：
     * {@code sha256(accessSecret ‖ "kean-im-refresh-token")} → 32 字节 → 带 Base64Url 前缀的
     * 长度无关表示（{@code Keys.hmacShaKeyFor} 按字节长度挑 HS256/384/512，
     * 前缀只是为了让派生结果在任何字节长度下都能安全喂给它）。
     */
    private static SecretKey deriveRefreshSecret(String accessSecret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(accessSecret.getBytes(StandardCharsets.UTF_8));
            byte[] derived = digest.digest("kean-im-refresh-token".getBytes(StandardCharsets.UTF_8));
            String material = "kean-im-refresh:" + Base64.getUrlEncoder().withoutPadding().encodeToString(derived);
            return Keys.hmacShaKeyFor(material.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256，无法派生 IM refreshToken 密钥", ex);
        }
    }

    /**
     * 一次签发的产物。
     *
     * @param accessToken 交给 im-server 的 {@code {cmd:0}} 登录帧使用
     * @param refreshToken 供将来 im-platform 上线后换票使用；本期无服务端消费方
     * @param accessExpireAt accessToken 的绝对过期时间（epoch 毫秒）
     */
    public record ImTokenPair(String accessToken, String refreshToken, long accessExpireAt) {
    }
}
