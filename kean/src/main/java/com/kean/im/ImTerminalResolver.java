package com.kean.im;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 「这个请求来自哪个终端」的<b>唯一</b>推断落点（阶段 C 抽取，无行为变化）。
 *
 * <h2>为什么需要它（而不是各写一份）</h2>
 * <p>这个映射规则原先只存在于 {@code ImController.resolveTerminal}（取 box token 时决定
 * JWT 里的 {@code info.terminal}）。阶段 C-3 的「给自己其它终端推送」（{@code sendToSelf} 语义）
 * <b>必须知道发送者当前终端</b>才能把它排除掉，于是同一份规则有了第二个使用方。</p>
 * <p>两条硬要求决定了不能复制一份：</p>
 * <ol>
 *   <li><b>取票时的 terminal 与发送时的 terminal 必须是同一个值</b>。box 的
 *       {@code sendToSelf} 是拿 {@code sender.getTerminal()}（来自 JWT）去跳过当前终端的；
 *       如果 kean 发送侧按另一套规则算终端，会出现「跳过的是 APP，实际连的是 WEB」
 *       ⇒ 自己收到自己的消息（重复气泡），或者该同步的终端没同步；</li>
 *   <li>规则一旦分裂就再也对不齐（客户端不会为这件事发版）。所以这里抽成一个类，
 *       {@code ImController} 与 {@code ImMultiTerminalEchoService} <b>都</b>调它。</li>
 * </ol>
 *
 * <h2>规则（与抽取前逐字节一致，不允许「顺手优化」）</h2>
 * <p>优先级：{@code ?terminal=} 显式参数 → {@code X-Kean-Device} 请求头关键字 → 默认 APP(1)。</p>
 * <table border="1">
 *   <caption>可接受的写法</caption>
 *   <tr><th>来源</th><th>取值</th><th>结果</th></tr>
 *   <tr><td>显式参数</td><td>{@code web} / {@code 0}</td><td>WEB = 0</td></tr>
 *   <tr><td>显式参数</td><td>{@code app} / {@code 1}</td><td>APP = 1</td></tr>
 *   <tr><td>显式参数</td><td>{@code pc} / {@code 2}</td><td>PC = 2</td></tr>
 *   <tr><td>显式参数</td><td>其它任何值（含拼错）</td><td>回落请求头 / 默认（<b>不报错</b>）</td></tr>
 *   <tr><td>请求头 {@code X-Kean-Device}</td><td>含 {@code web}</td><td>WEB = 0</td></tr>
 *   <tr><td>请求头 {@code X-Kean-Device}</td><td>含 {@code pc} / {@code windows} / {@code mac}</td><td>PC = 2</td></tr>
 *   <tr><td>请求头 {@code X-Kean-Device}</td><td>含 {@code android} / {@code ios} / {@code app}</td><td>APP = 1</td></tr>
 *   <tr><td>都没有</td><td>——</td><td>APP = 1（{@code ImTokenService.DEFAULT_TERMINAL} 同值）</td></tr>
 * </table>
 *
 * <h2>⚠️ 已知的未证实点（阶段 C 的遗留风险，不是本类的 bug）</h2>
 * <p>{@code X-Kean-Device} 由客户端 {@code uni-kean/src/utils/request.ts} 的
 * {@code deviceLabel()} 填（已核对：每个请求都带），但它填的是<b>设备名</b>（如 {@code Windows 浏览器}），
 * 这里做的是<b>关键字包含</b>匹配。因此「H5 跑在手机上」这类组合下推断出的终端与真实终端可能不同
 * —— 这与抽取之前的行为<b>完全一样</b>（取票时就已经是这么推断的），本轮<b>不做修复</b>。
 * 正确的终态（{@code docs/ops/im-platform-migration.md} §6.2 / §6.4.2）是客户端
 * <b>显式</b>按真实终端取票（{@code GET /api/im/token?terminal=0|1|2}），那是客户端改造，属阶段 C 的后续项。</p>
 */
public final class ImTerminalResolver {

    /** box-im {@code IMTerminalType.WEB.code() = 0}。 */
    public static final int WEB = 0;

    /** box-im {@code IMTerminalType.APP.code() = 1}。 */
    public static final int APP = 1;

    /** box-im {@code IMTerminalType.PC.code() = 2}。 */
    public static final int PC = 2;

    /**
     * 推不出终端时的默认值 = {@code APP(1)}。
     *
     * <p>与 {@code ImTokenService.DEFAULT_TERMINAL}、{@code ImSenderService.sender()} 里的
     * 常量字面量<b>同值</b>：kean 是 uni-app，默认按 APP 更贴近事实（理由见
     * {@code ImController.resolveTerminal} 的原注释）。</p>
     */
    public static final int DEFAULT_TERMINAL = APP;

    /** box-im {@code IMTerminalType} 的全部终端码（WEB / APP / PC）。 */
    public static final int[] TERMINALS = {WEB, APP, PC};

    /** 终端码 → 名字（仅用于日志）。 */
    private static final String[] TERMINAL_NAMES = {"WEB", "APP", "PC"};

    private ImTerminalResolver() {
    }

    /**
     * 解析终端码：显式参数优先，其次请求头，最后默认值。
     *
     * @param explicit 显式值（{@code web}/{@code app}/{@code pc} 或 {@code 0}/{@code 1}/{@code 2}）；
     *                 非法值/null 时继续看请求头
     * @param request  当前请求；为 {@code null} 时直接用默认值
     * @return 一定是 {@link #WEB} / {@link #APP} / {@link #PC} 之一，<b>永不返回 null</b>
     */
    public static int resolve(String explicit, HttpServletRequest request) {
        Integer parsed = parse(explicit);
        if (parsed != null) {
            return parsed;
        }
        return fromHeader(request == null ? null : request.getHeader("X-Kean-Device"));
    }

    /**
     * 解析<b>当前线程正在处理的请求</b>的终端码（发送路径用这个）。
     *
     * <p>没有请求上下文（例如 WebSocket 线程、定时任务、单元测试）时返回
     * {@link #DEFAULT_TERMINAL}，<b>不抛异常</b> —— 对一个「尽力而为」的同步动作来说，
     * 猜不到终端时按默认值处理比让发送路径多一个失败点要好。</p>
     */
    public static int currentRequestTerminal() {
        return fromHeader(currentRequestDevice());
    }

    /** 当前请求的 {@code X-Kean-Device} 头；没有请求上下文时返回 {@code null}。 */
    public static String currentRequestDevice() {
        try {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if (attributes instanceof ServletRequestAttributes servletAttributes) {
                HttpServletRequest request = servletAttributes.getRequest();
                return request == null ? null : request.getHeader("X-Kean-Device");
            }
        } catch (Exception ignored) {
            // 读请求上下文绝不该影响业务：拿不到就按默认终端。
        }
        return null;
    }

    /** 终端码 → 名字（仅用于日志；未知值返回 {@code UNKNOWN(n)} 而不是抛异常）。 */
    public static String name(int terminal) {
        return terminal >= 0 && terminal < TERMINAL_NAMES.length
                ? TERMINAL_NAMES[terminal]
                : "UNKNOWN(" + terminal + ")";
    }

    /** 是否是 box 认识的终端码。 */
    public static boolean isValid(int terminal) {
        return terminal == WEB || terminal == APP || terminal == PC;
    }

    /**
     * 显式取值 → 终端码。
     *
     * <p><b>刻意不抛异常、也不吞掉无意义的值报错</b>：非法值返回 {@code null}，
     * 由调用方继续往下推断（与抽取前 {@code ImController.parseTerminal} 的行为一致）。</p>
     */
    private static Integer parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toLowerCase()) {
            case "web", "0" -> WEB;
            case "app", "1" -> APP;
            case "pc", "2" -> PC;
            default -> null;
        };
    }

    /**
     * {@code X-Kean-Device} 原文 → 终端码（关键字包含匹配）。
     *
     * <p>⚠️ <b>判断顺序不可调换</b>：原来的写法就是先 {@code web}、再 {@code pc/windows/mac}、
     * 最后 {@code android/ios/app}。像 {@code Windows 浏览器} 这种设备名同时含 {@code windows}
     * 与 {@code 浏览器}（不含 web 小写子串）时落到 PC；而 {@code Chrome on Windows} 里
     * 不含 {@code web} 子串，也落到 PC。调换顺序会让某些设备名换一个终端，
     * 而终端的用途是「跳过我自己」（C-3），换了就会变成「自己收到自己」。</p>
     */
    private static int fromHeader(String device) {
        if (device == null) {
            return DEFAULT_TERMINAL;
        }
        String normalized = device.toLowerCase();
        if (normalized.contains("web")) {
            return WEB;
        }
        if (normalized.contains("pc") || normalized.contains("windows") || normalized.contains("mac")) {
            return PC;
        }
        if (normalized.contains("android") || normalized.contains("ios") || normalized.contains("app")) {
            return APP;
        }
        return DEFAULT_TERMINAL;
    }
}
