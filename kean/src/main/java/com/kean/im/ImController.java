package com.kean.im;

import com.kean.common.ErrorCode;
import com.kean.common.Result;
import com.kean.exception.BizException;
import com.kean.security.SecurityUtils;
import com.kean.security.TokenRevokeService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
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
 * <h2>被封禁用户一律不发 token</h2>
 * <p>本端点<b>额外做一次封禁校验</b>：命中的直接 403 {@link ErrorCode#ACCOUNT_BANNED}
 * （与 {@code JwtAuthFilter} 拦住被封用户时<b>完全相同的</b>错误码与返回体形状）。</p>
 *
 * <p>为什么在 {@code JwtAuthFilter} 已经拦了一道之后还要拦：</p>
 * <ol>
 *   <li><b>纵深防御，不让票流出</b>。被封用户手上的旧 kean token 会被
 *       {@code TokenRevokeService} 作废 / 拉黑，所以正常情况下他连请求都发不进来；
 *       但只要存在任何一个「换到了新 kean token」的窗口（多端登录、作废位点与
 *       封禁标记的写入时序差、将来放行匿名路径），仅靠过滤器就会出现
 *       「被封了却拿到一张 im-server 认得的票」。IM 侧的封禁拦截要等 im-server 打补丁
 *       （见 {@code ImKickService} 的 TODO），<b>在那之前，票源这一层必须自己守住</b>；</li>
 *   <li>im-server 用的是它自己那份 {@code jwt.accessToken.secret} 独立验签，
 *       <b>不认识 kean 的 token 黑名单</b>。也就是说，只凭「kean 侧登录态失效」并不能
 *       阻止这张票去握手 —— 少发一张票，就少一条要等补丁才能堵住的路。</li>
 * </ol>
 *
 * <p>判断用的就是项目既有的封禁口径 {@link TokenRevokeService#isBanned(Long)}
 * （{@code AccountBanServiceImpl} 封禁时写入的 {@code kean:user:banned:{userId}}），
 * <b>没有另造一套</b>；{@code AccountBanServiceImpl} 也因此一行都不用改。</p>
 *
 * <h2>未启用时的语义</h2>
 * <p>后端没配 {@code IM_JWT_SECRET} 时仍返回 200，只是 {@code data.enabled = false}，
 * 客户端据此保持 IM 通道关闭 —— 与 {@code FieldCipher} 在缺 {@code DATA_ENC_KEY} 时
 * “只 WARN 不报错”的既有风格一致。</p>
 *
 * <p>注意<b>顺序</b>：先判 IM 是否启用，再判封禁。IM 没启用时这张票根本不存在，
 * 返回 {@code enabled=false}（一个不含任何凭证的正常响应）比抛 403 更贴合
 * 「IM 通道关闭」这个事实。</p>
 */
@RestController
@RequestMapping("/api/im")
public class ImController {

    private static final Logger log = LoggerFactory.getLogger(ImController.class);

    private final ImTokenService imTokenService;

    /**
     * 封禁态的唯一权威来源（与 {@code JwtAuthFilter} 用的是同一个 Bean、同一枚键）。
     * 这里只<b>读</b>它，不做任何写入。
     */
    private final TokenRevokeService tokenRevokeService;

    /**
     * 影子用户物化（阶段 A 新增）。取票成功之后<b>追加</b>一次调用，
     * 让「用户真的活着并且要用 IM」这个天然时机顺带把名片补进 {@code im_user}
     * （见 {@code docs/ops/im-platform-migration.md} §3 阶段 A 与 §10 第 4 处改动）。
     *
     * <p>⚠️ 它是<b>可选、默认关闭</b>的旁路动作：{@link ImShadowUserService#enabled()} 为
     * {@code false}（默认）时整个调用是 no-op；即使打开，{@link ImShadowUserService#ensureShadowUser(Long)}
     * 内部也自己 try/catch，<b>任何失败都只记一行 WARN，不会影响取票</b>。</p>
     */
    private final ImShadowUserService imShadowUserService;

    public ImController(ImTokenService imTokenService,
                        TokenRevokeService tokenRevokeService,
                        ImShadowUserService imShadowUserService) {
        this.imTokenService = imTokenService;
        this.tokenRevokeService = tokenRevokeService;
        this.imShadowUserService = imShadowUserService;
    }

    /**
     * @param terminal 可选。显式覆盖终端类型，取值 {@code web}/{@code app}/{@code pc}
     *                 或 {@code 0}/{@code 1}/{@code 2}（box-im {@code IMTerminalType}）。
     *                 不传时按 {@code X-Kean-Device} 请求头推断，推不出就用 {@code app}。
     * @throws BizException 当前用户被封禁时抛出（403 / {@link ErrorCode#ACCOUNT_BANNED}），
     *                      由 {@code GlobalExceptionHandler} 统一转成 {@code Result} 信封
     */
    @GetMapping("/token")
    public Result<ImTokenVO> token(
            @RequestParam(required = false) String terminal,
            HttpServletRequest request
    ) {
        if (!imTokenService.enabled()) {
            return Result.ok(ImTokenVO.disabled());
        }
        // 未登录时 currentUserId() 自己抛 40100，所以这里拿到的 userId 一定非空。
        Long userId = SecurityUtils.currentUserId();
        // 被封禁 → 拒绝发票（IM 侧「拒绝被封用户握手」的补丁还没上，票源这层必须先拦住）。
        if (tokenRevokeService.isBanned(userId)) {
            throw new BizException(ErrorCode.ACCOUNT_BANNED, bannedMessage(userId));
        }
        // 终端的推断规则已抽到 ImTerminalResolver（阶段 C-3 的「跳过当前终端」必须与取票用同一套规则，
        // 见该类注释）。这里只是换个调用点，行为与抽取前逐字节一致。
        ImTokenService.ImTokenPair pair =
                imTokenService.issue(userId, ImTerminalResolver.resolve(terminal, request));
        materializeShadowUser(userId);
        return Result.ok(new ImTokenVO(true, pair.accessToken(), pair.refreshToken(), pair.accessExpireAt()));
    }

    /**
     * 取票成功后<b>追加</b>一次影子用户物化（阶段 A）。
     *
     * <p>三条约束（都不可放宽）：</p>
     * <ul>
     *   <li><b>顺序</b>：必须在 {@code issue(...)} 之后、{@code return} 之前 ——
     *       取票本身是主路径，物化只是顺路，绝不能因为它改变「票能不能拿到」；</li>
     *   <li><b>吞异常</b>：这里再包一层 try/catch。<b>即使</b> {@code ensureShadowUser} 将来被改成会外抛，
     *       取票也不会因此 500（它内部本来也已经 try/catch，这里是纵深防御）；</li>
     *   <li><b>默认关闭</b>：开关是 {@code kean.im.shadow-user-enabled}（默认 {@code false}，
     *       见 {@code ImShadowUserService}）—— 不配这一行就等于不存在，不查库、不写库、无日志噪声。</li>
     * </ul>
     *
     * <p>⚠️ 物化失败<b>不代表</b>取票失败，所以这里<b>不</b>抛任何异常、也<b>不</b>改响应体。</p>
     */
    private void materializeShadowUser(Long userId) {
        try {
            imShadowUserService.ensureShadowUser(userId);
        } catch (Exception ex) {
            log.warn("[IM 影子用户] 取票时物化失败（已忽略，不影响取票），userId={}：{}", userId, ex.getMessage());
        }
    }

    /**
     * 封禁原因：优先用 {@code AccountBanServiceImpl} 写入的具体文案（例如举报处理的原因），
     * 缺失时回落到通用文案。
     *
     * <p>写法与 {@code JwtAuthFilter.writeBanned} 保持一致，用户在「取票失败」和
     * 「普通请求被拦」两个场景看到的是同一句话，不会以为是两个不同的问题。</p>
     */
    private String bannedMessage(Long userId) {
        String stored = tokenRevokeService.bannedMessage(userId);
        return StringUtils.hasText(stored) ? stored : ErrorCode.ACCOUNT_BANNED.getMessage();
    }

    /*
     * ⚠️ 这里原有两个私有方法 resolveTerminal / parseTerminal。阶段 C-3 把它们<b>原样搬进</b>
     * {@link ImTerminalResolver}（连同「显式参数 → X-Kean-Device → 默认 APP」的优先级与
     * 「先 web、再 pc/windows/mac、最后 android/ios/app」的判断顺序），本类改为调用它：
     *   · 原因是 C-3 的「给自己其它终端推送」也必须知道发送者当前终端，
     *     而它<b>必须与取票时写进 JWT 的那个值相同</b>（box 的 sendToSelf 是拿
     *     JWT 里的 sender.terminal 去跳过当前终端的）；两处各写一份迟早会漂移；
     *   · 顺带把「ImTokenService.issue 需要一个 Integer」这层装箱消掉了：
     *     resolver 返回 int，autobox 由编译器完成，取值完全相同。
     */
}
