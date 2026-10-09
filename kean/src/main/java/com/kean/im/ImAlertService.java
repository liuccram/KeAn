package com.kean.im;

import com.kean.service.MailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IM 监控告警的<b>唯一出口</b>（第 ③ 项）：把巡检发现的问题变成一封<b>可操作</b>的运维邮件。
 *
 * <h2>不新建邮件通道</h2>
 * <p>复用现有 {@link MailService}（其 {@code sendAlert} 与验证码邮件共用同一个
 * {@code JavaMailSender} Bean 和同一份 {@code kean.mail.*} 配置），
 * <b>没有引入任何新依赖、新配置节、新 SMTP 通道</b>。</p>
 *
 * <h2>收件人</h2>
 * <ol>
 *   <li>{@code KEAN_IM_ALERT_TO}（新环境变量，与项目既有风格一致：直接读环境变量）；</li>
 *   <li>为空则回落到 {@code MAIL_USERNAME}（运维邮箱，与 {@code spring.mail.username} 同源）；</li>
 *   <li>两者都为空 → <b>只记 ERROR 日志</b>，不抛异常、不阻止启动。</li>
 * </ol>
 *
 * <h2>开关</h2>
 * <p>{@code KEAN_IM_ALERT_ENABLED}，<b>默认 true</b>。设为 {@code false} 时降级为纯日志
 * （ERROR 级别，且每轮都会打 —— 关掉告警的人必须能从日志里看到问题）。</p>
 *
 * <h2>冷却（必须有）</h2>
 * <p>同一类告警（{@link #KIND_QUEUE_BACKLOG} / {@link #KIND_DELIVERY_FAILURE} /
 * {@link #KIND_QUEUE_RESIDUE}）在 {@code kean.im.alert-cooldown-minutes}（默认 <b>30 分钟</b>）
 * 内<b>只发一次</b>；冷却期内无论「恢复」还是「再次触发」都只记日志。</p>
 *
 * <p><b>实现取舍</b>：冷却表是进程内的
 * {@code ConcurrentHashMap<String, Long>}（告警类别 → 上次发信时间戳）。</p>
 * <ul>
 *   <li>✅ 好处：零依赖、零 Redis 写入（Redis 本身可能就是出问题的那一环，
 *       把冷却状态放在 Redis 上会在最需要告警时失效 —— 这是刻意的）；</li>
 *   <li>⚠️ <b>取舍：多实例部署时每个实例各有一份冷却表，同一类告警可能被重复发送
 *       （最多 N 份，N = 实例数）</b>。当前形态是单实例（{@code im:max_server_id=1} 量级），
 *       而且巡检本身已经被 {@code @SchedulerLock} 收敛成「同一时刻只有一个实例在跑」，
 *       所以实际重复概率很低。若将来要多实例且要求精确去重，应把冷却位点挪到 Redis
 *       （键另议），而不是在这里加锁；</li>
 *   <li>⚠️ 进程重启会清空冷却表（可能立刻补发一封），这是可接受的 —— 重启后重新确认状态比漏报好。</li>
 * </ul>
 *
 * <h2>告警历史（内存态，重启即丢）</h2>
 * <p>每一条<b>检出的问题</b>（包括被冷却抑制、没发出去的那种）都会记进进程内的
 * {@link ArrayDeque} 环形缓冲，容量 {@code kean.im.alert-history-size}（默认 50 条），
 * 由 {@link #history(int)} 以「新的在前」读出，供管理端
 * {@code GET /api/admin/im/alerts} 展示。<b>不落库、不加表、不加迁移</b> ——
 * 进程重启后历史清空，需要长期留存请查运维邮箱或 kean 日志（关键字 {@code [IM 告警]}）。</p>
 *
 * <h2>「告警失败不影响业务」的保证</h2>
 * <p>{@link #alert} 的整个方法体包在一个大 try/catch 里，
 * 任何异常（收件人为空、邮件未配置、SMTP 超时、模板拼接错误）都只落 {@code log.warn} /
 * {@code log.error}，<b>绝不外抛</b>。本类不参与任何业务事务，
 * 也从不写 Redis、从不写数据库 —— 因此它不可能影响
 * {@code ChatServiceImpl.send()} 这条主链路。</p>
 *
 * <h2>绝不打密钥</h2>
 * <p>邮件正文与日志里<b>只有 Redis 键名与命令</b>，不含 {@code REDIS_PASSWORD} /
 * {@code MAIL_PASSWORD} / {@code IM_JWT_SECRET} 等任何凭据；
 * 建议的 {@code redis-cli} 命令也刻意不带 {@code -a} 参数（见 {@link #cleanupHint}）。</p>
 */
@Service
public class ImAlertService {

    private static final Logger log = LoggerFactory.getLogger(ImAlertService.class);

    /** 告警类别 1：队列堆积（长度超阈值）。 */
    public static final String KIND_QUEUE_BACKLOG = "queue-backlog";

    /** 告警类别 2：投递失败次数超阈值。 */
    public static final String KIND_DELIVERY_FAILURE = "delivery-failure";

    /** 告警类别 3：旧 serverId 残留队列。 */
    public static final String KIND_QUEUE_RESIDUE = "queue-residue";

    private static final long DEFAULT_COOLDOWN_MINUTES = 30L;

    /** 告警历史（内存环形缓冲）默认容量；管理端 {@code GET /api/admin/im/alerts} 最多能取到这么多条。 */
    private static final int DEFAULT_HISTORY_SIZE = 50;

    private final MailService mailService;

    /** {@code KEAN_IM_ALERT_ENABLED}，默认 true。 */
    private final boolean alertEnabled;

    /** 已解析的收件人（{@code KEAN_IM_ALERT_TO} → {@code MAIL_USERNAME}）；可能为空串。 */
    private final String recipient;

    /** 同一类告警的发信间隔（毫秒）。 */
    private final long cooldownMs;

    /**
     * 冷却表：告警类别 → 上次<b>尝试发送</b>的时间戳（epoch 毫秒）。
     *
     * <p>只被巡检线程写、被巡检线程读；用 {@code ConcurrentHashMap} 是为了万一将来
     * 有别的调用方（例如健康端点）读它也不会炸，不是为了防并发。</p>
     */
    private final Map<String, Long> lastSentAt = new ConcurrentHashMap<>();

    private final int historySize;

    /**
     * 告警历史环形缓冲（<b>内存态，重启即丢</b>，见 {@link ImAlertRecord} 的类注释）。
     *
     * <p>放入的是<b>每一次检出的问题</b>（包含被冷却抑制、没发邮件的那种），
     * 这样管理端看板在冷却期内也能看到问题仍在持续，而不是「一片空白」。</p>
     *
     * <p>用 {@code ArrayDeque} 而不是 {@code ConcurrentLinkedDeque}：容量控制需要
     * 「加一个、丢一个」的原子性，这里用最朴素的 {@code synchronized} 保证；
     * 写入方只有一个（巡检线程），读取方是管理端 HTTP 线程，锁竞争可以忽略。</p>
     */
    private final Deque<ImAlertRecord> history = new ArrayDeque<>();

    public ImAlertService(
            MailService mailService,
            @Value("${KEAN_IM_ALERT_ENABLED:true}") boolean alertEnabled,
            @Value("${KEAN_IM_ALERT_TO:}") String alertTo,
            @Value("${MAIL_USERNAME:}") String mailUsername,
            @Value("${kean.im.alert-cooldown-minutes:" + DEFAULT_COOLDOWN_MINUTES + "}") long cooldownMinutes,
            @Value("${kean.im.alert-history-size:" + DEFAULT_HISTORY_SIZE + "}") int historySize
    ) {
        this.mailService = mailService;
        this.alertEnabled = alertEnabled;
        // 回落链：KEAN_IM_ALERT_TO → MAIL_USERNAME → 空（只记日志）。
        this.recipient = StringUtils.hasText(alertTo)
                ? alertTo.trim()
                : (StringUtils.hasText(mailUsername) ? mailUsername.trim() : "");
        // 防御：配成 0 或负数会让冷却失效（每轮都发信），这里兜到默认值。
        this.cooldownMs = (cooldownMinutes > 0 ? cooldownMinutes : DEFAULT_COOLDOWN_MINUTES) * 60_000L;
        this.historySize = historySize > 0 ? historySize : DEFAULT_HISTORY_SIZE;
        log.info("[IM 告警] 邮件告警 enabled={}，收件人来源={}，冷却={} 分钟，历史缓冲={} 条（内存态）"
                        + "（配置项 KEAN_IM_ALERT_ENABLED / KEAN_IM_ALERT_TO，回落 MAIL_USERNAME）",
                alertEnabled,
                StringUtils.hasText(alertTo) ? "KEAN_IM_ALERT_TO"
                        : (this.recipient.isEmpty() ? "未配置（只记日志）" : "MAIL_USERNAME"),
                this.cooldownMs / 60_000L, this.historySize);
    }

    /**
     * 冷却时长（分钟）。供巡检任务在告警正文里如实写出「多久内不会再发」，
     * 避免文档/正文与真实配置不一致。
     */
    public long cooldownMinutes() {
        return cooldownMs / 60_000L;
    }

    /** 历史缓冲容量（条）。管理端用它做 {@code limit} 的上界。 */
    public int historySize() {
        return historySize;
    }

    /**
     * 最近若干条告警记录（<b>新的在前</b>）。
     *
     * <p>只读内存、不含任何凭据；管理端 {@code GET /api/admin/im/alerts} 直接映射成 VO。</p>
     *
     * @param limit 最多返回多少条；&le;0 视为容量上限，超过容量则按容量截断
     */
    public List<ImAlertRecord> history(int limit) {
        int max = limit > 0 ? Math.min(limit, historySize) : historySize;
        synchronized (history) {
            List<ImAlertRecord> result = new ArrayList<>(Math.min(max, history.size()));
            for (ImAlertRecord record : history) {
                if (result.size() >= max) {
                    break;
                }
                result.add(record);
            }
            return result;
        }
    }

    /**
     * 发一条告警（失败/冷却/未配置一律静默降级为日志），并把它记进内存历史。
     *
     * <p><b>本方法永不抛异常</b>，调用方（{@link ImQueueMonitorService}）可以放心直接调用，
     * 不需要再包 try/catch。</p>
     *
     * @param kind         告警类别（冷却的粒度），用本类的 {@code KIND_*} 常量
     * @param level        级别 {@code WARN} / {@code ERROR}
     * @param summary      问题类型（一句人话）
     * @param currentValue 当前值的可读描述（供管理端看板与历史）
     * @param threshold    阈值与配置项名（同上）
     * @param keys         相关 Redis 键名（不含凭据）
     * @param subject      邮件主题
     * @param body         <b>可操作</b>的邮件正文：问题类型 / 当前值 / 阈值 / 相关 Redis 键 / 建议命令
     */
    public void alert(String kind,
                      String level,
                      String summary,
                      String currentValue,
                      String threshold,
                      List<String> keys,
                      String subject,
                      String body) {
        boolean mailSent = false;
        String mailNote = null;
        try {
            long now = System.currentTimeMillis();
            Long last = lastSentAt.get(kind);
            if (last != null && now - last < cooldownMs) {
                long remainingSeconds = (cooldownMs - (now - last)) / 1000L;
                mailNote = "冷却中（同类告警 " + cooldownMinutes() + " 分钟内只发一次，剩余 " + remainingSeconds + " 秒）";
                log.warn("[IM 告警][冷却中，不发邮件] 类别={}，剩余 {} 秒。问题详情：\n{}",
                        kind, remainingSeconds, body);
            } else if (!alertEnabled) {
                mailNote = "邮件告警已关闭（KEAN_IM_ALERT_ENABLED=false）";
                log.error("[IM 告警][邮件告警已关闭 KEAN_IM_ALERT_ENABLED=false，只记日志] 类别={}\n{}", kind, body);
            } else if (recipient.isEmpty()) {
                mailNote = "无收件人（KEAN_IM_ALERT_TO 与 MAIL_USERNAME 均为空）";
                log.error("[IM 告警][无收件人：KEAN_IM_ALERT_TO 与 MAIL_USERNAME 均为空，只记日志] 类别={}\n{}",
                        kind, body);
            } else if (!mailService.ready()) {
                mailNote = "邮件通道未就绪（kean.mail.enabled / kean.mail.from 未配置）";
                log.error("[IM 告警][邮件通道未就绪，只记日志] 类别={}\n{}", kind, body);
            } else {
                // 先记冷却、再发信：SMTP 抖动时宁可 30 分钟内不再重试，也不要每 60 秒重发造成邮件风暴。
                lastSentAt.put(kind, now);
                try {
                    mailService.sendAlert(recipient, subject, body);
                    mailSent = true;
                    log.info("[IM 告警] 已发送告警邮件 类别={} 收件人={}", kind, recipient);
                } catch (Exception ex) {
                    // 对外（告警历史 / 管理端接口）只写异常类型，不写原始 message ——
                    // 邮件客户端异常里可能带 SMTP 主机名这类基础设施信息，没必要外泄。
                    mailNote = "邮件发送失败（" + ex.getClass().getSimpleName() + "）";
                    log.warn("[IM 告警] 告警邮件发送失败（已忽略，不影响任何业务；{} 分钟内不再重试）类别={}：{}",
                            cooldownMinutes(), kind, ex.getMessage());
                }
            }
        } catch (Exception ex) {
            // 兜底：连时间戳/字符串拼接都出问题也不能影响调用方（巡检线程）。
            mailNote = "告警流程自身异常（" + ex.getClass().getSimpleName() + "）";
            log.warn("[IM 告警] 告警流程自身异常（已忽略，不影响任何业务）：{}", ex.getMessage());
        }
        remember(new ImAlertRecord(LocalDateTime.now(), kind, level, summary,
                currentValue, threshold, keys == null ? List.of() : List.copyOf(keys), mailSent, mailNote));
    }

    /** 记入历史缓冲（丢最旧的），失败也不能抛。 */
    private void remember(ImAlertRecord record) {
        try {
            synchronized (history) {
                history.addFirst(record);
                while (history.size() > historySize) {
                    history.removeLast();
                }
            }
        } catch (Exception ex) {
            log.debug("[IM 告警] 写入告警历史失败（已忽略）：{}", ex.getMessage());
        }
    }

    /**
     * 生成「危险操作请人工确认」的清理命令片段。
     *
     * <p>刻意<b>只报告不自动删</b>：残留队列里可能是尚未推送的真实消息，
     * 删掉就永久丢失；而且 {@code DEL} 是唯一能真正缓解「Redis 内存被无界队列吃满」的手段，
     * 必须由运维看过内容再决定。所以这里把命令写进告警正文，让人复制执行。</p>
     *
     * <p>命令里<b>不带 {@code -a <密码>}</b>：密码属于凭据，不进邮件、不进日志。
     * 运维请通过 {@code REDISCLI_AUTH} 环境变量之类的受控方式提供认证信息。</p>
     *
     * @param keys 需要处理的队列键（调用方保证是 {@code im:message:*} 这类只含安全字符的键）
     */
    public static String cleanupHint(Iterable<String> keys) {
        StringBuilder sb = new StringBuilder();
        sb.append("  1) 先看内容与长度（不会删任何东西）：\n");
        for (String key : keys) {
            sb.append("       redis-cli -n 0 LLEN ").append(key).append('\n');
            sb.append("       redis-cli -n 0 LRANGE ").append(key).append(" 0 2\n");
        }
        sb.append("  2) 确认 im-server 不会再消费这些 serverId（看 im:max_server_id 与实例数）：\n");
        sb.append("       redis-cli -n 0 GET im:max_server_id\n");
        sb.append("  3) ⚠️ 确认无人依赖后再清理（DEL 会永久丢弃队列里未推送的消息，本程序不会自动执行）：\n");
        for (String key : keys) {
            sb.append("       redis-cli -n 0 DEL ").append(key).append('\n');
        }
        return sb.toString();
    }
}
