package com.kean.im;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 阶段 C-5：<b>「消息保存」从双写切到单写的总开关</b>
 * （{@code kean.im.message-single-write-enabled}，<b>默认 {@code false}</b> = 维持现状）。
 *
 * <h2>它到底管什么（一条线，不多不少）</h2>
 * <p>线上现状（已由用户核实）：<b>读已经全在 box</b>
 * （{@code kean.im.read-source=box}、{@code kean.im.unread-source=box}），
 * 但<b>写仍然是双写</b>（{@code KEAN_IM_MESSAGE_MIRROR_ENABLED=true}：
 * kean 的 {@code chat_message} 落库 + box 的 {@code im_platform.im_private_message} 镜像）。
 * 本开关就是那把「把 kean 那一半写停掉、只留 box」的闸。</p>
 *
 * <table border="1">
 *   <caption>开关语义（{@code false} = 与新增本开关之前<b>逐字节相同</b>）</caption>
 *   <tr><th>写入点（文件:方法）</th><th>目标表</th><th>{@code false}（默认）</th><th>{@code true}（单写）</th></tr>
 *   <tr><td>{@code ChatServiceImpl.send} → {@code chatMessageMapper.insert}</td>
 *       <td>kean {@code chat_message}</td><td><b>照写</b></td><td><b>跳过</b>（消息只进 box 的
 *       {@code im_platform.im_private_message}）</td></tr>
 *   <tr><td>{@code ChatServiceImpl.send} → {@code chatSessionMapper.updateById}</td>
 *       <td>kean {@code chat_session}</td><td><b>照写</b></td>
 *       <td><b>照写（⚠️ 刻意保留，见下）</b></td></tr>
 *   <tr><td>{@code ChatServiceImpl.markRead} 的位点 / 未读清零 / {@code chat_message.status=3}</td>
 *       <td>kean {@code chat_session} / {@code chat_message}</td><td><b>照写</b></td>
 *       <td><b>照写（⚠️ 刻意保留，见下）</b></td></tr>
 *   <tr><td>{@code ChatServiceImpl.open} → 新建会话</td>
 *       <td>kean {@code chat_session}</td><td><b>照写</b></td>
 *       <td><b>照写（⚠️ 刻意保留，见下）</b></td></tr>
 *   <tr><td>box 的 {@code im_platform.im_private_message} 镜像</td><td>box</td>
 *       <td>照写</td><td><b>照写（它就是单写之后唯一的写目标）</b></td></tr>
 * </table>
 *
 * <h2>⭐ 为什么 {@code true} 只停「消息正文」的写，而 {@code chat_session} 照写</h2>
 * <p>这是本轮一个<b>刻意的、有理由的缩小</b>（原始需求写的是「停止写 kean 的
 * {@code chat_message} / {@code chat_session}」）。理由只有一条，但很硬：
 * <b>kean 的 {@code chat_session} 不是「消息正文」，它是本项目的「会话身份」本身</b> ——
 * 客户端每一个聊天接口都拿 {@code chat_session.id} 当参数，而 box 侧<b>根本没有这张表</b>
 * （box 的私聊「会话」是 {@code im_friend} + {@code conv_key} 拼出来的，见
 * {@code docs/ops/im-platform-migration.md} §1.4）。一旦停止写它：</p>
 * <ol>
 *   <li><b>新会话建不出来</b>：{@code ChatServiceImpl.open} → {@code createSession}
 *       → {@code chatSessionMapper.insert} 是「点对方主页发私信 / 任务详情点联系对方」的<b>唯一</b>落点
 *       （{@code chat_session} 上只有 {@code uk_chat_session_users (user_a_id, user_b_id)}，
 *       没有任何「无会话也能收消息」的通道）。它一停，{@code POST /api/chats} 只能返回一个
 *       {@code id=null} 的会话，客户端接着就会拿 {@code /api/chats/null/messages} 发消息 ⇒ 404</li>
 *   <li><b>既有会话也读不出来</b>：{@code requireOwnedSession} 就是
 *       {@code chatSessionMapper.selectById}，它同时是 {@code send / markRead / detail / messages}
 *       的<b>前置校验</b> —— 这不是「旧数据」的问题，是「立刻、全站聊天打不开」；</li>
 *   <li>会话的 {@code a_read_seq / b_read_seq} 是 box 侧 {@code status} 的<b>推导输入</b>
 *       （{@code ImMessageMirrorService.boxStatusOf} 与 {@code markReadInBox} 同源），
 *       它不写，box 的已读判据就失去参照；</li>
 *   <li>会话的 {@code last_seq_no} 还是 {@code ChatSeqServiceImpl} 的 Redis 冷启动下界
 *       （{@code floor = max(MAX(seq_no), last_seq_no)}）：Redis 被清空/TTL 过期后，
 *       没有它就只能从 0 重新播种 ⇒ 已经用过的 seq_no 会在 box 里出现<b>两行同号消息</b>
 *       （box 的 {@code im_private_message} 没有 {@code (conv_key, seq_no)} 唯一键，
 *       kean 的那条唯一键 {@code uk_chat_message_session_seq} 又已经不写了），
 *       而 {@code last_seq_no} 还出现在会话列表/详情的 {@code ChatSessionVO.lastSeqNo} 里。</li>
 * </ol>
 * <p>⚠️ 因此：<b>「{@code chat_session} 也停写」这件事必须先有 box 侧的对等物</b>
 * （{@code im_friend} 投影 + 会话 id 的代理/兑换），那需要 box 侧的源码与接口才能做，
 * 本轮<b>没有猜着改</b>（见交付说明「哪些停下没做」）。本开关的名字与语义因此写成
 * 「message-single-write」而不是「chat-single-write」，以免误导。</p>
 *
 * <h2>⚠️ 单写开启后的硬前提（启动时会 WARN 出来）</h2>
 * <p>{@code kean.im.read-source} <b>必须</b>是 {@code box}。否则新消息会「写进 box 却拉不回来」：
 * 增量拉取走 kean 的 {@code chat_message}（已经不再更新），用户看到的是冻结的历史。
 * 生产现状已经是 {@code box}，所以这条只是把「前提」显式写出来，不是新增要求。</p>
 *
 * <h2>默认 {@code false} 时为什么零影响（逐条论证）</h2>
 * <ol>
 *   <li>本类<b>不持有连接、不读 Redis、不发 SQL、不建 DataSource</b>：
 *       构造期只读一个已经算好的布尔量（{@code @Value} 默认 {@code false}）；</li>
 *   <li>它只有两处调用点（都在 {@code send} 的落库判定与 {@code catchUpAfterSeq} 的两条
 *       「回退到 kean 交叉确认」上），写法都是
 *       {@code if (imSingleWriteRoleService.messageSingleWriteEnabled()) { 让位 } else { 原来那一段 }}；
 *       默认 {@code false} 时走的就是<b>原来那一段</b>，一条语句、一个分支判断都不多；</li>
 *   <li>它<b>不动</b> {@code .env*} / {@code application*.yml}（只靠 {@code @Value} 默认值）、
 *       <b>不加</b> Flyway、<b>不删</b>任何镜像 / 补偿 / 比对代码、<b>不碰</b> {@code chat_message}
 *       与 {@code chat_session} 的任何数据；</li>
 *   <li>唯一新增的输出是一行 <b>INFO</b> 启动日志（{@link #summary()}）——
 *       与本项目其它 IM 开关（{@code [IM 镜像投递]} / {@code [IM 未读来源]} / {@code [IM 读取来源]} /
 *       {@code [IM 实时职责]}）风格一致，无副作用。</li>
 * </ol>
 *
 * <h2>回退（一键、秒级、无需回填）</h2>
 * <p>把 {@code KEAN_IM_MESSAGE_SINGLE_WRITE_ENABLED} 设回 {@code false}（或直接删掉那一行）
 * 并重启 kean：{@code ChatServiceImpl.send} 立刻恢复向 {@code chat_message} 落库，
 * 镜像链路（{@code kean.im.message-mirror-enabled}）本来就一直在跑。<b>不需要任何数据回填</b> ——
 * 本开关只控制「写不写 kean」，不删任何行；单写期间 kean 表里缺的只是「那几天的消息行」，
 * 而读路径（{@code read-source=box}）本来就不看它们。</p>
 */
@Service
public class ImSingleWriteRoleService {

    private static final Logger log = LoggerFactory.getLogger(ImSingleWriteRoleService.class);

    /**
     * 开关名（同时用于启动日志、WARN 文案与 {@code ChatServiceImpl} 的日志，避免拼写漂移）。
     * 环境变量形式：{@code KEAN_IM_MESSAGE_SINGLE_WRITE_ENABLED}。<b>默认 {@code false}</b>。
     */
    public static final String PROPERTY_MESSAGE_SINGLE_WRITE_ENABLED = "kean.im.message-single-write-enabled";

    /**
     * 单写是否开启。<b>默认 {@code false}</b>（= 仍双写，kean 照写 + 镜像照推）。
     *
     * <p>⚠️ 默认值纪律：{@code false} 表示「与新增本开关之前逐字节相同」，
     * 所以<b>部署本身零影响</b>；真正的切换由部署后改环境变量
     * {@code KEAN_IM_MESSAGE_SINGLE_WRITE_ENABLED=true} + 重启 kean 完成。</p>
     */
    private final boolean messageSingleWriteEnabled;

    public ImSingleWriteRoleService(
            @Value("${kean.im.message-single-write-enabled:false}") boolean messageSingleWriteEnabled
    ) {
        this.messageSingleWriteEnabled = messageSingleWriteEnabled;
        // 一行 INFO：把「消息当前往哪里写」一次性说清（与 [IM 实时职责] 同一风格）。
        // 我们踩过「以为切了其实没切」的坑（只看环境变量只能证明它被注入，
        // 不能证明 @Value 解析成功），所以这里把开关名、解析值、各表的写/不写都打出来。
        log.info("[IM 消息落库] {}", summary());
        if (messageSingleWriteEnabled) {
            log.warn("[IM 消息落库] {}", riskNote());
        }
    }

    // ------------------------------------------------------------------
    // 开关
    // ------------------------------------------------------------------

    /**
     * 是否「单写」：{@code true} = {@code ChatServiceImpl.send} <b>跳过</b>向 kean
     * {@code chat_message} 的落库，只写 box 的 {@code im_platform.im_private_message}。
     *
     * <p><b>默认 {@code false}</b>（仍双写）。⚠️ 它被读的地方只有两处，且都在「写侧」的语境里：
     * ① {@code ChatServiceImpl.send}（决定要不要落 kean 的 {@code chat_message}）；
     * ② {@code ImOfflineQueryService.catchUpAfterSeq}（单写后 kean 的 {@code chat_message}
     * 不再新增，那两条「回退到 kean 交叉确认」必须让位）。
     * 它<b>不</b>参与「从哪读消息」的口径（那是 {@code read-source} 的事）。</p>
     */
    public boolean messageSingleWriteEnabled() {
        return messageSingleWriteEnabled;
    }

    /**
     * ③ 那行汇总的唯一文案来源（启动日志用它，避免多处口径漂移）。
     *
     * <p>报告四件事：「消息正文写到哪」/「kean 的 chat_message 还写不写」/
     * 「kean 的 chat_session 还写不写（含理由）」/「回退方式」。</p>
     */
    public String summary() {
        if (!messageSingleWriteEnabled) {
            return "消息落库=双写（保持现状，kean 的 chat_message + box 的 im_platform.im_private_message）"
                    + "（" + PROPERTY_MESSAGE_SINGLE_WRITE_ENABLED + "=false）；"
                    + "kean 的 chat_message=照写；kean 的 chat_session=照写（会话身份/未读/位点）；"
                    + "box 镜像=照推（kean.im.message-mirror-enabled 决定）；"
                    + "回退方式=无需回退（当前就是双写）；切换方式=把 "
                    + PROPERTY_MESSAGE_SINGLE_WRITE_ENABLED + " 设为 true 并重启 kean";
        }
        return "消息落库=单写（只写 box 的 im_platform.im_private_message）"
                + "（" + PROPERTY_MESSAGE_SINGLE_WRITE_ENABLED + "=true）；"
                + "kean 的 chat_message=【不再写入】（发送路径的落库被跳过）；"
                + "kean 的 chat_session=【仍然写入】（刻意保留：会话身份/新建会话/未读计数/已读位点/"
                + "last_seq_no，box 侧没有这张表，停了会全站聊天打不开）；"
                + "正在运行的镜像/补偿/比对=镜像【照常执行】（它拿的是内存里的消息对象，"
                + "只是 kean id 为 null），补偿/比对则因为 chat_message 不再新增而【扫不到新消息】；"
                + "回退方式=把 " + PROPERTY_MESSAGE_SINGLE_WRITE_ENABLED
                + " 设回 false（或删掉那一行）并重启 kean ⇒ 立刻恢复双写，无需数据回填";
    }

    /**
     * 单写开启时的风险提示（与 {@code ImRealtimeRoleService.riskNote()} 同一写法：
     * 返回一段给 WARN 用的文案；本类只在 {@code true} 时调用它，所以永不为 {@code null}）。
     *
     * <p>⚠️ 这些风险<b>都不会报错、不会有 500</b>，只会表现为「某处看不到新消息」或
     * 「只写进 box 的行在 kean 侧查不到」，所以必须主动喊一声，
     * 把「无声失效」变成「日志里看得见的一行」。</p>
     */
    public String riskNote() {
        return "⚠️ 单写已开启（" + PROPERTY_MESSAGE_SINGLE_WRITE_ENABLED + "=true）："
                + "① kean 的 chat_message 【从此不再更新】，回退需要把本开关设回 false 并重启 kean"
                + "（只控制写，不删任何行，无需回填）；"
                + "② 任何仍读 kean chat_message 的路径都会读到【越来越旧甚至为空】的数据："
                + "文件归属校验 FileAccessGuard.isChatParticipant（聊天图片）、"
                + "增量拉取的完整性护栏 ImOfflineQueryService.countKeanAfterSeq、"
                + "举报消息的校验/删除/取发送人 ReportServiceImpl、"
                + "seq 冷启动播种 ChatSeqServiceImpl.maxSeqNoInDb、"
                + "镜像补偿/比对 ImMessageMirrorService —— 其中【完整性护栏已在单写模式下自动跳过】"
                + "（否则每 8 秒的增量拉取都会回退到空的 kean 表，新消息将永远显示不出来）；"
                + "③ 硬前提：kean.im.read-source 必须=box。若它仍是 kean，"
                + "新消息会「写进 box 却拉不回来」（增量拉取走的是已经冻结的 chat_message），"
                + "用户看到的是冻结的历史 —— 这是【用户可见】的，切之前先确认它=box；"
                + "④ kean 侧的消息幂等（同 (sender_id, local_id) 不重复落库）随 chat_message 一起失效，"
                + "幂等改由 box 的 uk(send_id, local_id) 承担（客户端重试同一 localId 仍然安全）。";
    }
}
