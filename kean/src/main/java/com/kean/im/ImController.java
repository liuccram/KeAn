package com.kean.im;

import com.kean.common.Result;
import com.kean.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * box-im 兼容 token 的签发端点（阶段 D2-(c) 新增）。
 *
 * <p>{@code GET /api/im/token} —— 登录用户取一对 box-im 兼容 token，用于连接 im-server 的
 * {@code ws://<im-host>:8878/im}（注意路径是 {@code /im}，由 im-server 的
 * {@code WebSocketServerProtocolHandler("/im")} 固定）。</p>
 *
 * <h2>鉴权</h2>
 * <p><b>没有改动任何既有安全逻辑</b>：路径落在 {@code /api/**} 下，而
 * {@code SecurityConfig} 的 {@code .anyRequest().authenticated()} 与
 * {@code JwtAuthFilter} 本来就覆盖它，因此这里天然要求携带课安自己的
 * {@code Authorization: Bearer <kean token>}。{@code JwtService} /
 * {@code JwtAuthFilter} / {@code SecurityConfig} 一行未改。</p>
 *
 * <h2>未启用时的语义</h2>
 * <p>后端没配 {@code IM_JWT_SECRET} 时仍返回 200，只是 {@code data.enabled = false}，
 * 客户端据此保持 IM 通道关闭 —— 与 {@code FieldCipher} 在缺 {@code DATA_ENC_KEY} 时
 * “只 WARN 不报错”的既有风格一致。</p>
 */
@RestController
@RequestMapping("/api/im")
public class ImController {

    private final ImTokenService imTokenService;

    public ImController(ImTokenService imTokenService) {
        this.imTokenService = imTokenService;
    }

    /**
     * @param terminal 可选。显式覆盖终端类型，取值 {@code web}/{@code app}/{@code pc}
     *                 或 {@code 0}/{@code 1}/{@code 2}（box-im {@code IMTerminalType}）。
     *                 不传时按 {@code X-Kean-Device} 请求头推断，推不出就用 {@code app}。
     */
    @GetMapping("/token")
    public Result<ImTokenVO> token(
            @RequestParam(required = false) String terminal,
            HttpServletRequest request
    ) {
        if (!imTokenService.enabled()) {
            return Result.ok(ImTokenVO.disabled());
        }
        ImTokenService.ImTokenPair pair =
                imTokenService.issue(SecurityUtils.currentUserId(), resolveTerminal(terminal, request));
        return Result.ok(new ImTokenVO(true, pair.accessToken(), pair.refreshToken(), pair.accessExpireAt()));
    }

    /**
     * 决定写进 token 的 {@code info.terminal}。
     *
     * <p>优先级：显式 query 参数 → {@code X-Kean-Device} 请求头里的平台关键字 → 默认 app。</p>
     *
     * <p><b>为什么默认 app 而不是 web：</b>box-im 的 {@code im-uniapp} 客户端用的就是
     * {@code IMTerminalType.APP}/{@code PC}，本仓 {@code uni-kean} 同样是 uni-app，
     * 所以默认值按 APP=1 更贴近事实。这个值只影响 box-im 服务端
     * {@code UserChannelCtxMap} 里 {@code (userId, terminal)} 的槽位划分与
     * “同终端重复登录才挤下线”的判定，选错不会导致连接失败。</p>
     */
    private static Integer resolveTerminal(String terminal, HttpServletRequest request) {
        Integer explicit = parseTerminal(terminal);
        if (explicit != null) {
            return explicit;
        }
        String device = request == null ? null : request.getHeader("X-Kean-Device");
        if (device != null) {
            String normalized = device.toLowerCase();
            if (normalized.contains("web")) {
                return 0;
            }
            if (normalized.contains("pc") || normalized.contains("windows") || normalized.contains("mac")) {
                return 2;
            }
            if (normalized.contains("android") || normalized.contains("ios") || normalized.contains("app")) {
                return 1;
            }
        }
        return 1;
    }

    private static Integer parseTerminal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toLowerCase();
        return switch (value) {
            case "web", "0" -> 0;
            case "app", "1" -> 1;
            case "pc", "2" -> 2;
            default -> null;
        };
    }
}
