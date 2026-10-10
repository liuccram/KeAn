package com.kean.im;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kean.entity.ChatMessage;
import com.kean.entity.ChatSession;
import com.kean.enums.ChatMessageStatus;
import com.kean.mapper.ChatMessageMapper;
import com.kean.mapper.ChatSessionMapper;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * 阶段 B-1：把课安的私聊消息<b>物化镜像</b>进 box-im 的
 * {@code im_platform.im_private_message}（<b>只写，不读</b>）。
 *
 * <h2>它与 {@link ImSenderService} 是什么关系（⚠️ 两条互不相干的通道）</h2>
 * <table border="1">
 *   <caption>两条「镜像」的边界</caption>
 *   <tr><th></th><th>{@link ImSenderService}</th><th>本类</th></tr>
 *   <tr><td>写哪里</td><td>Redis 队列 {@code im:message:private:{serverId}}（im-server 拉）</td>
 *       <td>MySQL 表 {@code im_platform.im_private_message}（im-platform 读）</td></tr>
 *   <tr><td>开关</td><td>{@code kean.im.mirror-enabled}（+ {@code IM_JWT_SECRET}）</td>
 *       <td>{@code kean.im.message-mirror-enabled}（<b>默认 false</b>）</td></tr>
 *   <tr><td>失败后果</td><td>消息推不到客户端，库里仍在</td><td>box 侧少一行影子数据，<b>本次发送照常成功</b></td></tr>
 * </table>
 * <p>⚠️ 两个开关<b>刻意独立</b>：本类<b>不</b>依赖 {@code IM_JWT_SECRET}，也<b>不</b>调用
 * {@link ImSenderService}（不写队列 —— 队列那条路已经由 {@code ChatServiceImpl.send} 既有的调用完成）。
 * 本类只做一件事：把 kean 已经落库成功的那一行，幂等地补一份到 box 的表里。</p>
 *
 * <h2>为什么是「主 DataSource + 全限定表名」，不是新 DataSource</h2>
 * <p>{@code im_platform} 与 kean 在<b>同一个 MySQL 实例</b>上，kean 的数据库账号已被授权访问
 * {@code im_platform.*}，项目里已有先例（{@link ImShadowUserService} 用主 DataSource +
 * {@code JdbcTemplate} 写 {@code im_platform.im_user}）。因此这里照抄同一套：
 * SQL 里写全限定表名 {@code im_platform.im_private_message}，
 * <b>不新增 DataSource、不动任何数据源配置、不引入新依赖</b>。</p>
 *
 * <h2>⭐⭐ 失败策略：kean 为主，box 写失败<em>绝不</em>回滚 kean（用户明确选择 a）</h2>
 * <p>这是本类最重要的设计约束。四条机制叠在一起才成立 —— <b>尤其第 2 条容易想当然，见下面的注解</b>：</p>
 * <ol>
 *   <li><b>异常在方法内部就被吃掉。</b>{@link #mirrorQuietly} 全包 try/catch，
 *       任何异常都只落 {@code log.warn} + 计数，<b>返回 boolean，永不外抛</b>。
 *       这是最要紧的一条：Spring 的 {@code @Transactional} 只有在异常<b>穿过代理</b>时才回滚，
 *       异常根本不出本类，就没有「box 抖动导致 kean 回滚」这条路径；</li>
 *   <li><b>box 的写跑在 {@code REQUIRES_NEW} 的独立事务里</b>
 *       （{@link #mirrorTemplate}，{@link org.springframework.transaction.annotation.Propagation#REQUIRES_NEW}）。
 *       ⚠️ 这一条不是装饰：{@code JdbcTemplate.update(...)} 用主 DataSource 取连接，
 *       而 Spring 的 {@code DataSourceUtils} 会优先复用<b>当前线程已绑定的那个连接</b>
 *       —— 如果什么都不做，box 的 INSERT 会直接跑在 {@code ChatServiceImpl.send} 的
 *       {@code @Transactional} 事务里（同一个连接、同一个事务）。
 *       那时即便异常被吞掉，一个真正的 SQL 错误（表不存在、权限不足、超列宽……）
 *       仍然可能把整个事务标记成 {@code rollbackOnly}，用户照样发不出消息。
 *       {@code REQUIRES_NEW} 把 box 的写<b>挂起</b>外层事务、另开一个连接与事务，
 *       于是「box 失败」在事务层也被物理隔离开；
 *   </li>
 *   <li><b>调用方再包一层 try/catch</b>（纵深防御）：即使本类将来被改成会外抛，
 *       {@code ChatServiceImpl.send} 也不会因此回滚；</li>
 *   <li><b>唯一索引让「补写」永远安全</b>。失败的那条消息靠
 *       {@link #compensate()} 幂等补写，重复执行不会产生第二行（见下）。</li>
 * </ol>
 * <p>⚠️ {@code REQUIRES_NEW} 的已知取舍（都可接受，但要知道）：</p>
 * <ul>
 *   <li>发送路径会<b>短暂占用两个数据库连接</b>（外层 kean 事务一个 + 这条独立语句一个）。
 *       这是单条 INSERT、毫秒级；真正需要担心连接数时应该调大连接池，而不是退回「共用连接」；</li>
 *   <li>{@code im_platform} 的表与 kean 的表<b>没有任何外键关系</b>，因此内层事务
 *       不会去等外层事务持有的行锁（不会自锁）。若将来有人给 box 的表加上指向 kean 表的外键，
 *       这条结论就失效了 —— 那时必须回头重新评估；</li>
 *   <li>{@code REQUIRES_NEW} <b>不改变</b>本类「只写一行、幂等、可重放」的性质
 *       —— 内外事务写的是两张互不相干的表。</li>
 * </ul>
 *
 * <h2>逐列映射（以 {@code docs/sql/im-platform.sql} 的 {@code im_private_message} 为准）</h2>
 * <table border="1">
 *   <caption>im_platform.im_private_message 每一列的来源</caption>
 *   <tr><th>box 列</th><th>类型 / 约束</th><th>来源</th><th>确定度</th></tr>
 *   <tr><td>{@code id}</td><td>bigint auto_increment PK</td>
 *       <td><b>不显式写</b>，交给 box 自增（它同时是 box 的消息游标：{@code loadOfflineMessage(minId)}）</td>
 *       <td>✅ 建表 SQL + {@code PrivateMessageServiceImpl}</td></tr>
 *   <tr><td>{@code local_id}</td><td>varchar(32) NULL</td>
 *       <td>{@code chat_message.local_id}（kean 的幂等键：客户端生成，服务端未收到时补 32 位 UUID）。
 *           长度 &gt; 32 时<b>拒绝镜像</b>（{@link MirrorSkipException}，不计入 failed）</td>
 *       <td>✅ 建表 SQL + {@code ChatServiceImpl.generatedLocalId}</td></tr>
 *   <tr><td>{@code seq_no}</td><td>int NOT NULL</td>
 *       <td>{@code chat_message.seq_no}（kean 的会话内序号，Redis Lua 分配）。
 *           <b>显式指定</b>，绝不调用 box 自己的 {@code getNextSeqNo} —— 这是「同一时刻只有一个分配器」的落点</td>
 *       <td>✅ 阶段 B 的核心要求（{@code im-platform-migration.md} §3.B.1 第 2 条）</td></tr>
 *   <tr><td>{@code send_id}</td><td>bigint NOT NULL</td><td>{@code chat_message.sender_id}</td>
 *       <td>✅</td></tr>
 *   <tr><td>{@code recv_id}</td><td>bigint NOT NULL</td>
 *       <td><b>会话里的另一方</b>：{@code session.user_a_id == senderId ? user_b_id : user_a_id}
 *           （kean 的 {@code chat_message} <b>没有</b> recv_id 列，必须由会话推出）</td>
 *       <td>✅ 与 {@code ChatServiceImpl.send} 里算 {@code peerId} 的写法同源</td></tr>
 *   <tr><td>{@code conv_key}</td><td>varchar(64) NOT NULL</td>
 *       <td>{@link #convKey(Long, Long)} = {@code Math.min(a,b) + "_" + Math.max(a,b)}</td>
 *       <td>✅✅ <b>本轮已逐字核对 box 源码</b>
 *           {@code com.bx.implatform.util.ConvUtil.buildConvKey}：
 *           {@code return Math.min(userId1, userId2) + "_" + Math.max(userId1, userId2);}
 *           （{@code im-platform-migration.md} §12 的 U3 到此可销项）</td></tr>
 *   <tr><td>{@code content}</td><td>text NULL（utf8mb4）</td>
 *       <td>{@code chat_message.content}。</td>
 *       <td>⚠️ 见下面「content 与图片」小节</td></tr>
 *   <tr><td>{@code type}</td><td>tinyint NOT NULL</td>
 *       <td>{@link #boxMessageType(String)}：kean {@code TEXT} → {@code 0}，
 *           {@code IMAGE} → {@code 1}（未知值回落 {@code 0}）</td>
 *       <td>✅ 与 {@link ImSenderService} 的队列镜像<b>同一套映射</b>（两条通道给 box 的 type 必须一致）</td></tr>
 *   <tr><td>{@code status}</td><td>tinyint NOT NULL</td>
 *       <td><b>按 kean 的会话已读位点判定</b>（{@link #boxStatusOf}）：
 *           这条消息对它的<b>接收方</b>而言满足 {@code seq_no <= 接收方在该会话的已读位点}
 *           就写 <b>{@code 3}</b>（{@code MessageStatus.READED}），否则写 <b>{@code 1}</b>（{@code DELIVERED}）。
 *           ⚠️ 位点（{@code a_read_seq} / {@code b_read_seq}）或 {@code seq_no} 为 NULL 一律当<b>未读</b>处理。
 *           <b>不再无条件写 1</b> —— 那正是线上「回填 60 条历史消息全写成已送达、
 *           切到 box 未读来源后两个用户突然看到 24 / 30 条未读」的根因（见类注释「已读状态」小节）</td>
 *       <td>✅ 已核对 box {@code MessageStatus}：0 PENDING / 1 DELIVERED / 2 RECALL / 3 READED。
 *           已读判据与 B-3 的 {@link ImUnreadQueryService#markReadInBox} <b>同源</b>
 *           （都是 {@code recv_id=本人 AND seq_no <= 位点}），两处不可能给出不同口径</td></tr>
 *   <tr><td>{@code send_time}</td><td><b>datetime(3)</b>，默认 {@code current_timestamp(3)}</td>
 *       <td>{@code chat_message.created_at}（{@code AuditMetaObjectHandler} 填的落库真实值，
 *           与 HTTP 返回给客户端的 {@code ChatMessageVO.createdAt} 同源），
 *           经 {@link Timestamp#valueOf(LocalDateTime)} 绑定</td>
 *       <td>✅ <b>是 datetime(3)，不是 bigint 毫秒</b>；box 侧 Java 类型是 {@code java.util.Date}
 *           （{@code PrivateMessage.sendTime}），JDBC 层就是 DATETIME</td></tr>
 * </table>
 *
 * <h2>content 与图片（⚠️ 与终态 D1 的已知差异，刻意与队列镜像保持一致）</h2>
 * <p>kean 的 {@code chat_message.content} 在 {@code IMAGE} 时是对象存储的 objectKey
 * （形如 {@code chat/{userId}/{uuid}.png}，见 {@code ChatServiceImpl.send} 的校验）。</p>
 * <p>box 自己在非文字消息里存的是 <b>JSON</b>（{@code PrivateMessageServiceImpl.validMessage} 对
 * 非 TEXT 调用 {@code JSON.parse(dto.getContent())}）。也就是说：<b>我们写进去的图片行，
 * 其 content 不是 box 期望的 JSON 形状</b>。</p>
 * <p>本轮<b>刻意仍然写裸 objectKey</b>，理由三条：</p>
 * <ol>
 *   <li>本轮 box 侧<b>没有任何读方</b>（阶段 B 的读仍在 kean），所以形状差异暂时不产生任何可见后果；</li>
 *   <li>{@link ImSenderService} 的队列镜像<b>已经在给客户端传同样的裸 objectKey</b>，
 *       两条通道保持一致，才不会出现「同一张图，队列里是一个值、表里是另一个值」的分叉；</li>
 *   <li>富媒体 content 的<b>确切 JSON 字段名在文档里仍是未证实项</b>
 *       （{@code im-platform-migration.md} §12 的 U2）。按未证实的形状提前写进去，
 *       反而会制造一个「看起来像对、其实可能错」的既成事实。</li>
 * </ol>
 * <p>⇒ 改造点已集中到 {@link #boxContent(ChatMessage)} 一个方法。D1 阶段确认 U2 之后，
 * 只改这一个方法即可（届时建议同时改 {@link ImSenderService} 的队列体，保持两条通道一致）。</p>
 *
 * <h2>幂等：唯一索引 {@code (send_id, local_id)} + {@code INSERT ... ON DUPLICATE KEY UPDATE}</h2>
 * <p>服务器上会单独给 box 的表加唯一索引 {@code (send_id, local_id)}（<b>不走 Flyway</b>
 * —— 那是另一个库）。本类的 SQL 按「该索引存在」写：</p>
 * <pre>
 * INSERT INTO im_platform.im_private_message (...)
 * VALUES (...)
 * ON DUPLICATE KEY UPDATE local_id = VALUES(local_id), send_id = VALUES(send_id)
 * </pre>
 * <ul>
 *   <li><b>为什么不用「先查后插」</b>：先查后插在两个并发补写之间有一个无法关闭的窗口
 *       （查不到 → 两边都插 → 两行），要关掉它就得在 box 的表上加锁或者再引一个分布式锁，
 *       代价远大于收益。<b>唯一索引是数据库层的原子判据</b>，交给它最省事也最可靠；</li>
 *   <li><b>UPDATE 子句刻意是「写回同值」</b>（{@code local_id = VALUES(local_id)} / {@code send_id = VALUES(send_id)}）：
 *       幂等重放时它<b>不改变任何列的值</b>，因此无论 box 侧后续把 {@code status} 改成
 *       3（已读）还是 2（撤回），我们的补写都<b>不会把它覆盖回去</b>；
 *       ⚠️ 这条边界<b>与「插入时按位点算 status」并不矛盾</b>（两者是「首次插入算对 / 重复时不动」的分工，
 *       见下面「已读状态」小节）：{@code status} 一旦落库就<b>只</b>由 B-3 的
 *       {@link ImUnreadQueryService#markReadInBox} 单向推进，本类的重放永不参与；</li>
 *   <li><b>必须先有那条唯一索引</b>。没有它时，{@code ON DUPLICATE KEY UPDATE} 只会对
 *       box 自己的主键冲突生效，重复补写可能产生两行。启动日志会明确提示这件事。</li>
 * </ul>
 *
 * <h2>⭐ 已读状态（{@code status}）：插入时按 kean 的已读位点算，重复时不动</h2>
 * <p><b>线上真实缺陷（已实测，勿再犯）</b>：本类早期版本在 {@code buildArgs} 里把
 * {@code status} 写成<b>常量 1</b>，于是补偿器回填历史消息时，<b>一律</b>把那些
 * 「kean 侧早已读过」的消息也写成「已送达(未读)」。实测现场：</p>
 * <pre>
 * kean 会话2：a_unread=0 b_unread=0 a_read_seq=49 b_read_seq=54 last_seq=54
 * box 表    ：按 status&lt;3 数出来，用户3 未读=24、用户5 未读=30   ← 全是回填写 1 造成的假未读
 * </pre>
 * <p>后果是<b>静默的、用户可见的回归</b>：一旦把未读来源切到 box
 * （{@code kean.im.unread-source=box}），这两个用户会突然看到 24 / 30 条未读，而且不报任何错。
 * 因此现在的规则是（{@link #boxStatusOf}）：</p>
 * <ol>
 *   <li>取<b>接收方</b>在该会话的已读位点：{@code recv_id} 是 {@code user_a_id} → {@code a_read_seq}，
 *       是 {@code user_b_id} → {@code b_read_seq}（否则视为读不到位点）；</li>
 *   <li>位点为 <b>NULL</b> ⇒ 当 {@code 0}（= 没读过，写 {@code 1}）；</li>
 *   <li>{@code seq_no} 为 <b>NULL</b> ⇒ 当未读（写 {@code 1}）——实际上这种行在
 *       {@link #buildArgs} 里会被直接 skip，这里只是不让它变成一条 NPE 路径；</li>
 *   <li>{@code seq_no <= 位点} ⇒ 写 <b>{@code 3}</b>（{@code READED}）；否则写 <b>{@code 1}</b>。</li>
 * </ol>
 * <p>新消息（{@link #mirrorPrivate}）走的是<b>同一个</b> {@link #buildArgs}：刚落库的消息
 * {@code seq_no} 一定大于接收方的位点，所以自然算成 {@code 1}（未读），与改动前一致。</p>
 * <p>这条判据与 B-3 的 {@link ImUnreadQueryService#markReadInBox} 的 UPDATE
 * （{@code recv_id=本人 AND seq_no <= 位点 AND status < 3}）<b>逐字同源</b>：
 * 「镜像写进去的未读」与「markRead 清掉的未读」用的是同一把尺子，不会出现
 * 「算得出、清不掉」的分叉。</p>
 * <p>⚠️ <b>已在线上被写错的历史行不会自动变回来</b>（本类的 UPSERT 刻意不覆盖 {@code status}）。
 * 兜底是一次性、默认关闭的对齐入口 {@link #repairReadStateFromKean()}（开关
 * {@code kean.im.message-mirror-repair-read-state-enabled}，默认 false），
 * 它与运维手工执行的那条 SQL 等价、<b>只把未读改成已读</b>、可重复执行。</p>
 *
 * <h2>默认全部关闭：不配 = 一条 box 的 SQL 都不发</h2>
 * <ul>
 *   <li>主写开关 {@code kean.im.message-mirror-enabled}，默认 <b>false</b>；
 *       false 时 {@link #mirrorQuietly} 在第一行返回，<b>不建 JdbcTemplate、不连库、
 *       不向 {@code im_platform} 发任何 SQL</b>；</li>
 *   <li>补偿器 {@code kean.im.message-mirror-compensate-enabled}，默认 <b>false</b>；</li>
 *   <li>比对器 {@code kean.im.message-mirror-compare-enabled}，默认 <b>false</b>；</li>
 *   <li>一次性已读对齐 {@code kean.im.message-mirror-repair-read-state-enabled}，默认 <b>false</b>
 *       （即便打开：也只在<b>启动时</b>跑一次，且必须主写开关可用）；</li>
 *   <li>补偿、比对与一次性对齐即使被打开，也必须主写开关可用（{@link #enabled()}）才真正跑。</li>
 * </ul>
 * <p>⚠️ <b>一处必须说清的例外（否则就是「以为关了其实有 SQL」）</b>：
 * {@link #compensate()} / {@link #compare()} 上的 {@code @SchedulerLock} 是 AOP 切面，
 * 它在方法体<b>之前</b>就会去读写 shedlock 的锁表（kean 自己的库，<b>不是</b> {@code im_platform}）。
 * 也就是说开关全关时，仍然存在「每 5 分钟 / 每 30 分钟各一次、只碰 shedlock 表」的锁开销
 * —— 这与 {@code TaskScheduleService} 每 30 秒已经在做的事完全同量级。
 * 对 {@code im_platform} 与 {@code chat_message} 的读写则<b>严格为零</b>。</p>
 *
 * <h2>计数与告警</h2>
 * <p>用 {@link LongAdder} 维护 {@code attempts / written / duplicate / failed / skipped}
 * （{@link ImDeliveryStats} 用的是同一套无锁计数思路；语义见
 * {@link #statsSnapshot()}）。补偿轮的失败数超阈值时复用
 * {@link ImAlertService} 发邮件（同一套冷却与收件人回落）。<b>计数不改变「吞异常」的既有行为。</b></p>
 */
@Service
public class ImMessageMirrorService {

    private static final Logger log = LoggerFactory.getLogger(ImMessageMirrorService.class);

    /** 跨库全限定表名（同实例跨 schema，见类注释；不新建 DataSource）。 */
    private static final String TABLE = "im_platform.im_private_message";

    /**
     * 与建表 SQL 一致的列宽：{@code local_id varchar(32)}。
     *
     * <p>超长时<b>拒绝镜像</b>而不是截断：截断会改变幂等键，从而破坏
     * {@code (send_id, local_id)} 的唯一性语义（两条不同的消息可能被截成同一个键）。</p>
     */
    private static final int MAX_LEN_LOCAL_ID = 32;

    /** {@code conv_key varchar(64)}：{@code min_max} 两个 bigint 拼起来最多 41 字符，留足余量。 */
    private static final int MAX_LEN_CONV_KEY = 64;

    /** box {@code MessageType.TEXT.code() = 0}。 */
    private static final int BOX_TYPE_TEXT = 0;

    /** box {@code MessageType.IMAGE.code() = 1}。 */
    private static final int BOX_TYPE_IMAGE = 1;

    /**
     * box {@code MessageStatus.DELIVERED.code() = 1}（建表注释写「1:已发送」）。
     *
     * <p>kean 落库时新消息也是 {@link ChatMessageStatus#SENT}(1) ⇒ 两边同义。
     * 刻意<b>不</b>写 box 自己在 HTTP 路径上用的 {@code PENDING(0)}：
     * kean 走的是无状态直写，没有「等 im-server 回执」这一步。</p>
     *
     * <p>⚠️ 它现在的角色是 {@link #boxStatusOf} 的<b>兜底值</b>（= 未读），
     * 不再是「无条件写死」的那个常量。</p>
     */
    private static final int BOX_STATUS_DELIVERED = 1;

    /**
     * box {@code MessageStatus.READED.code() = 3}（= kean 的
     * {@link ChatMessageStatus#READ}(3)，两边同值）。
     *
     * <p>它是 box 侧<b>已读的唯一标记</b>（{@code im_private_message} 没有 read_time 列），
     * 也是未读判据的另一半：{@link ImUnreadQueryService} 用 {@code status < 3} 数未读，
     * 本类在插入时按 kean 的已读位点决定写 {@code 3} 还是 {@code 1}。
     * 两处必须同值，否则会出现「镜像写 3、未读判据认不出」的静默错位。</p>
     */
    private static final int BOX_STATUS_READED = 3;

    /**
     * 一次性已读对齐（{@link #repairReadStateFromKean()}）的 UPDATE 语句模板。
     *
     * <p>与 B-3 的 {@link ImUnreadQueryService#markReadInBox} <b>同一个谓词</b>：
     * {@code recv_id = 本人 AND conv_key = 会话 AND seq_no <= 位点 AND status < 3}。
     * 最后的 {@code status < 3} 保证两件事：<b>只把未读改成已读</b>（绝不反向）、
     * 且<b>幂等</b>（已对齐过的行不会被重复改）。</p>
     */
    private static final String REPAIR_READ_STATE_SQL =
            "UPDATE " + TABLE + " SET status = " + BOX_STATUS_READED
                    + " WHERE recv_id = ? AND conv_key = ? AND seq_no <= ? AND status < " + BOX_STATUS_READED;

    /** 补偿/比对单轮扫描条数的硬上界（防止把配置写成一个夸张的数字后一次拉爆）。 */
    private static final int MAX_SCAN_LIMIT = 5000;

    /**
     * 「查 box 侧是否已存在」时每个查询最多带多少个 {@code (send_id, local_id)} 对。
     *
     * <p>取 200：单条 SQL 的占位符 400 个，远低于 MySQL 的包大小与
     * {@code max_allowed_packet} 限制，同时把「笛卡尔积返回行数」压在可控范围内。</p>
     */
    private static final int EXISTS_QUERY_CHUNK = 200;

    /** 告警类别 4：镜像补写失败次数超阈值。供 {@link ImAlertService} 做冷却分流。 */
    public static final String KIND_MESSAGE_MIRROR_FAILURE = "message-mirror-failure";

    /** 告警类别 5：镜像条数比对不一致。 */
    public static final String KIND_MESSAGE_MIRROR_MISMATCH = "message-mirror-mismatch";

    /**
     * 告警类别 6（阶段 B-3）：<b>未读数</b>比对不一致 —— kean 的
     * {@code chat_session.a_unread/b_unread} 与「按 box 的消息 status 推导出的未读数」对不上。
     *
     * <p>与 {@link #KIND_MESSAGE_MIRROR_MISMATCH} <b>分开</b>是刻意的：两者是不同性质的故障
     * （条数对不上 = 消息没写过去；未读对不上 = 已读投影没落下去），
     * 混在一个类别里会被 {@link ImAlertService} 的冷却互相压掉，而且排查方向完全不同。</p>
     */
    public static final String KIND_MESSAGE_MIRROR_UNREAD_MISMATCH = "message-mirror-unread-mismatch";

    /**
     * 列清单与 VALUES 占位符。
     *
     * <p>⚠️ <b>{@code id} 刻意不在里面</b>：box 的 {@code id} 是自增主键，同时也是它的消息游标
     * （{@code loadOfflineMessage(minId)} / {@code readedMessage}）。显式写入会与 box 自己的
     * 分配器打架 —— 这一点与 {@link ImShadowUserService} 对 {@code im_user.id} 的处理<b>相反</b>，
     * 原因是那边要求「同 id 复用」（铁律 L1），而消息 id 在终态里本来就归 box 分配。</p>
     */
    private static final String INSERT_COLUMNS =
            "local_id, seq_no, send_id, recv_id, conv_key, content, type, status, send_time";

    private static final String INSERT_VALUES = "?, ?, ?, ?, ?, ?, ?, ?, ?";

    /**
     * 冲突时「写回同值」（不是 {@code id = id} 之类的占位）：
     * 语义上明确是 no-op，且避免 MySQL 的「值未变化」快路径在不同版本上的行为差异。
     *
     * <p>⚠️ 这里<b>没有</b> {@code status} / {@code seq_no} / {@code content}：
     * 补写绝不覆盖 box 侧已经推进过的状态。与「插入时按 kean 的已读位点算 {@code status}」
     * 配套起来就是那条分工：<b>首次插入算对，幂等重放一行不动</b>
     * （所以 box 侧后来被 {@code markReadInBox} 改成 3 的行，永远不会被我们改回 1）。</p>
     */
    private static final String UPDATE_CLAUSE =
            "local_id = VALUES(local_id), send_id = VALUES(send_id)";

    private static final String UPSERT_SQL =
            "INSERT INTO " + TABLE + " (" + INSERT_COLUMNS + ") VALUES (" + INSERT_VALUES + ") "
                    + "ON DUPLICATE KEY UPDATE " + UPDATE_CLAUSE;

    private final ChatMessageMapper chatMessageMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final ImAlertService imAlertService;

    /**
     * 阶段 B-3：未读数的「按 box 口径」读数与「已读回写」都在这里。
     *
     * <p>本类的比对器只<b>读</b>它（{@link ImUnreadQueryService#unreadCountsByConvKey}），
     * 用来把 kean 的 {@code a_unread/b_unread} 与 box 推导值对照。
     * 开关（{@code kean.im.unread-source}）不在本类手里：由该服务自己判断，
     * 未开启时返回 {@code null}，本类只打一条 DEBUG。</p>
     */
    private final ImUnreadQueryService imUnreadQueryService;

    /** 主写开关，默认 <b>false</b>。 */
    private final boolean enabled;

    private final boolean compensateEnabled;
    private final boolean compareEnabled;

    /** 补偿窗口（天）与单轮上限（条）。 */
    private final int compensateDays;
    private final int compensateLimit;

    /** 比对窗口（天）与单轮会话上限（个）。 */
    private final int compareDays;
    private final int compareLimit;

    /** 判定「不一致」的最小差额（1 = 任何差异都报）。 */
    private final int compareDiffThreshold;

    /** 单轮补写失败数超过它 → 邮件告警。 */
    private final int failureAlertThreshold;

    /** 补偿周期（毫秒）与比对周期（毫秒），仅用于日志与告警正文。 */
    private final long compensateIntervalMs;
    private final long compareIntervalMs;

    /**
     * 一次性已读对齐开关，默认 <b>false</b>（{@link #repairReadStateFromKean()}）。
     *
     * <p>⚠️ 它<b>只</b>决定「启动时要不要跑那一次 UPDATE」，不影响任何在线路径：
     * 打开后跑一次就该改回 false（对齐是幂等的，重复跑也只是 0 行）。</p>
     */
    private final boolean repairReadStateEnabled;

    /** 一次性已读对齐的会话窗口（天）：只扫「最近这么多天内更新过」的会话。 */
    private final int repairReadStateDays;

    /**
     * {@code im_platform} 的访问通道；<b>主写开关关闭时为 {@code null}</b>
     * （与 {@link ImShadowUserService} 同一取舍：构造期就不去解析数据源）。
     */
    private final JdbcTemplate imPlatformJdbc;

    /**
     * box 侧写入的<b>独立事务模板</b>（{@code REQUIRES_NEW}），见类注释「失败策略」第 2 条。
     *
     * <p>为什么必须显式开新事务：{@code JdbcTemplate} 与调用方共用主 DataSource，
     * Spring 会把当前线程绑定的连接交还给 {@code JdbcTemplate} ——
     * 不显式挂起的话，box 的 INSERT 就是 kean 主事务的一部分，
     * 那时「异常被吞掉」也不足以保证外层事务不被标记 {@code rollbackOnly}。</p>
     *
     * <p>解析不到 {@link PlatformTransactionManager} 时为 {@code null}，
     * 此时退化成「直接用 {@code JdbcTemplate} 执行」（见 {@link #inBoxTransaction}）——
     * 那种情况下第 1、3 条机制仍然有效，只是少了一层事务隔离。
     * 这种情况只可能出现在被人为裁剪过的容器里（Spring Boot 的
     * {@code DataSourceTransactionManagerAutoConfiguration} 会提供该 Bean）。</p>
     */
    private final TransactionTemplate mirrorTemplate;

    // —— 计数（无锁，只增；重启归零）——
    private final LongAdder attempts = new LongAdder();
    private final LongAdder written = new LongAdder();
    private final LongAdder duplicates = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder skipped = new LongAdder();

    public ImMessageMirrorService(
            ChatMessageMapper chatMessageMapper,
            ChatSessionMapper chatSessionMapper,
            ImAlertService imAlertService,
            ImUnreadQueryService imUnreadQueryService,
            ObjectProvider<DataSource> dataSourceProvider,
            ObjectProvider<PlatformTransactionManager> transactionManagerProvider,
            @Value("${kean.im.message-mirror-enabled:false}") boolean enabled,
            @Value("${kean.im.message-mirror-compensate-enabled:false}") boolean compensateEnabled,
            @Value("${kean.im.message-mirror-compare-enabled:false}") boolean compareEnabled,
            @Value("${kean.im.message-mirror-compensate-days:7}") int compensateDays,
            @Value("${kean.im.message-mirror-compensate-limit:500}") int compensateLimit,
            @Value("${kean.im.message-mirror-compare-days:7}") int compareDays,
            @Value("${kean.im.message-mirror-compare-limit:200}") int compareLimit,
            @Value("${kean.im.message-mirror-compare-diff-threshold:1}") int compareDiffThreshold,
            @Value("${kean.im.message-mirror-failure-threshold:20}") int failureAlertThreshold,
            @Value("${kean.im.message-mirror-compensate-interval-ms:300000}") long compensateIntervalMs,
            @Value("${kean.im.message-mirror-compare-interval-ms:1800000}") long compareIntervalMs,
            @Value("${kean.im.message-mirror-repair-read-state-enabled:false}") boolean repairReadStateEnabled,
            @Value("${kean.im.message-mirror-repair-read-state-days:30}") int repairReadStateDays
    ) {
        this.chatMessageMapper = chatMessageMapper;
        this.chatSessionMapper = chatSessionMapper;
        this.imAlertService = imAlertService;
        this.imUnreadQueryService = imUnreadQueryService;
        this.enabled = enabled;
        this.compensateEnabled = compensateEnabled;
        this.compareEnabled = compareEnabled;
        this.compensateDays = Math.max(compensateDays, 1);
        this.compensateLimit = clamp(compensateLimit, MAX_SCAN_LIMIT);
        this.compareDays = Math.max(compareDays, 1);
        this.compareLimit = clamp(compareLimit, MAX_SCAN_LIMIT);
        this.compareDiffThreshold = Math.max(compareDiffThreshold, 1);
        this.failureAlertThreshold = Math.max(failureAlertThreshold, 1);
        this.compensateIntervalMs = Math.max(compensateIntervalMs, 1000L);
        this.compareIntervalMs = Math.max(compareIntervalMs, 1000L);
        this.repairReadStateEnabled = repairReadStateEnabled;
        this.repairReadStateDays = Math.max(repairReadStateDays, 1);
        this.imPlatformJdbc = enabled ? resolveJdbcTemplate(dataSourceProvider) : null;
        this.mirrorTemplate = enabled ? resolveTransactionTemplate(transactionManagerProvider) : null;
        // 启动即把「到底开没开」打出来。我们踩过「以为关了其实开了」的坑：只看 /proc/<pid>/environ
        // 只能证明环境变量被注入，不能证明 @Value 解析成功（键名拼错时会静默沿用默认值 false）。
        log.info("[IM 消息镜像] 主写 enabled={}（配置项 kean.im.message-mirror-enabled / 环境变量 "
                        + "KEAN_IM_MESSAGE_MIRROR_ENABLED）→ 实际生效={}，box 写事务模式={}；"
                        + "补偿 enabled={}（每 {}ms，窗口 {} 天，单轮上限 {} 条）；"
                        + "比对 enabled={}（每 {}ms，窗口 {} 天，单轮上限 {} 个会话，差额阈值 {}）；"
                        + "失败告警阈值={} 条/轮（类别 {} / {}）；目标表={}（主 DataSource，跨库全限定名）",
                enabled, enabled && imPlatformJdbc != null,
                this.mirrorTemplate == null ? "复用调用方连接（缺 PlatformTransactionManager）" : "REQUIRES_NEW（独立事务）",
                compensateEnabled, this.compensateIntervalMs, this.compensateDays, this.compensateLimit,
                compareEnabled, this.compareIntervalMs, this.compareDays, this.compareLimit, this.compareDiffThreshold,
                this.failureAlertThreshold, KIND_MESSAGE_MIRROR_FAILURE, KIND_MESSAGE_MIRROR_MISMATCH,
                TABLE);
        if (enabled && imPlatformJdbc != null) {
            log.info("[IM 消息镜像] ⚠️ 幂等依赖 box 表上的唯一索引 (send_id, local_id)。"
                    + "该索引由运维在服务器上单独添加（不走 Flyway，因为 im_platform 是另一个库）；"
                    + "若尚未添加：重复补写可能产生重复行。核对命令："
                    + "SHOW INDEX FROM im_platform.im_private_message;");
        }
        // 一次性已读对齐（默认关闭）：把「到底跑不跑」明确打出来，理由同上面那条启动日志。
        log.info("[IM 消息镜像] 一次性已读对齐 repairReadState enabled={}（配置项 "
                        + "kean.im.message-mirror-repair-read-state-enabled / 环境变量 "
                        + "KEAN_IM_MESSAGE_MIRROR_REPAIR_READ_STATE_ENABLED）→ 实际会执行={}；"
                        + "窗口 {} 天（配置项 ...-repair-read-state-days），单轮最多 {} 个会话；"
                        + "方向=只把未读(status<{})改成已读(status={})，幂等、绝不反向。"
                        + "⚠️ 它只在「历史回填把 status 无脑写成 {} 造成假未读」时用一次，"
                        + "跑完请把开关改回 false（不配即 false）",
                repairReadStateEnabled, repairReadStateEnabled && enabled && imPlatformJdbc != null,
                repairReadStateDays, compensateLimit,
                BOX_STATUS_READED, BOX_STATUS_READED, BOX_STATUS_DELIVERED);
        if (repairReadStateEnabled && !(enabled && imPlatformJdbc != null)) {
            // 最坏的一种组合：想对齐，但主写通道压根没建（enabled=false 或无 DataSource）⇒ 一行 SQL 都不会发。
            log.warn("[IM 消息镜像] 一次性已读对齐已配置为开启，但主写开关未生效"
                            + "（kean.im.message-mirror-enabled / 环境变量 KEAN_IM_MESSAGE_MIRROR_ENABLED）"
                            + "⇒ 本次不会执行任何 UPDATE，请先打开主写开关再重启");
        }
    }

    /**
     * 一次性已读对齐是否真正会跑：<b>配置打开</b> 且 <b>主写可用</b>
     * （对齐复用的是 {@code im_platform} 那同一条通道，主写关着时它不存在）。
     */
    public boolean repairReadStateReady() {
        return repairReadStateEnabled && enabled();
    }

    /**
     * 主写是否真正可用：<b>配置打开</b> 且 <b>数据源解析成功</b>。
     *
     * <p>⚠️ 与 {@link ImSenderService#enabled()} 不同，这里<b>不</b>看 {@code IM_JWT_SECRET}：
     * 写数据库与「im-server 能不能连上」是两件独立的事。</p>
     */
    public boolean enabled() {
        return enabled && imPlatformJdbc != null;
    }

    /** 补偿器是否真正会跑（配置打开 且 主写可用）。 */
    public boolean compensateReady() {
        return compensateEnabled && enabled();
    }

    /** 比对器是否真正会跑（配置打开 且 主写可用）。 */
    public boolean compareReady() {
        return compareEnabled && enabled();
    }

    /**
     * 计数的只读快照，格式：{@code attempts/written/duplicate/failed/skipped}
     * （供日志、告警正文与人工核对；无锁、无副作用）。
     *
     * <table border="1">
     *   <caption>五个计数的粒度</caption>
     *   <tr><th>字段</th><th>含义</th><th>粒度</th></tr>
     *   <tr><td>{@code attempts}</td><td>真正下发过 SQL 的次数</td><td><b>语句</b>：一次 {@link #mirrorPrivate} = 1</td></tr>
     *   <tr><td>{@code written}</td><td>新插入的影子行（{@code rows == 1}）</td><td><b>行</b></td></tr>
     *   <tr><td>{@code duplicate}</td><td>命中唯一键、未产生第二行（{@code rows != 1}）</td><td><b>行</b>（幂等重放会累加，属正常）</td></tr>
     *   <tr><td>{@code failed}</td><td>SQL 执行异常（box 库不可用 / 权限 / 表结构变化）</td><td><b>语句</b></td></tr>
     *   <tr><td>{@code skipped}</td><td>「什么都没做」的两类：开关关闭导致的 no-op，
     *       以及消息数据本身不满足 box 列约束（缺 {@code local_id} / {@code seq_no}、超列宽）</td>
     *       <td><b>消息</b></td></tr>
     * </table>
     *
     * <p>⚠️ 因此 {@code attempts} <b>不等于</b> {@code written + duplicate}：
     * {@code skipped} 的那些与 {@code attempts} 互斥（压根没下发 SQL）。
     * 这与 {@link ImDeliveryStats} 的口径是一致的 —— 判故障只看 {@code failed}。</p>
     */
    public String statsSnapshot() {
        return attempts.sum() + "/" + written.sum() + "/" + duplicates.sum() + "/"
                + failed.sum() + "/" + skipped.sum();
    }

    // ------------------------------------------------------------------
    // ① 主写：发送路径
    // ------------------------------------------------------------------

    /**
     * 镜像一条消息，<b>任何失败都只 log.warn + 计数，绝不外抛</b>。
     *
     * <p>这是 {@code ChatServiceImpl.send} 唯一需要调用的入口。它保证：</p>
     * <ul>
     *   <li>开关关闭 → 第一行返回，<b>不碰数据库</b>；</li>
     *   <li>参数不合法（消息/会话为空，或 kean 侧缺 {@code local_id} / {@code seq_no}）→ skip，不写、不告警；</li>
     *   <li>数据超列宽 → skip（不计入 failed，也不会让 MySQL 抛数据截断错误）；</li>
     *   <li>SQL 执行异常（box 库不可用 / 权限不足 / 表不存在）→ {@code log.warn} + 计数，
     *       <b>不影响调用方的事务</b>。</li>
     * </ul>
     *
     * @param message kean 刚落库的消息（{@code id} / {@code seq_no} / {@code local_id} /
     *                {@code created_at} 必须已被 {@code AuditMetaObjectHandler} 填好）
     * @param session 该消息所属会话（用于推 {@code recv_id} 与 {@code conv_key}）
     * @return {@code true} = 确实执行了一次 UPSERT（不代表「新插入」，重复幂等重放也是 true）
     */
    public boolean mirrorQuietly(ChatMessage message, ChatSession session) {
        if (!enabled()) {
            return false;
        }
        try {
            return mirrorPrivate(message, session);
        } catch (MirrorSkipException skip) {
            // 数据本身不满足 box 的列约束：既不是投递故障也不是「没开」，单独计数，不打 WARN。
            skipped.increment();
            log.debug("[IM 消息镜像] 跳过：{}（kean messageId={}）",
                    skip.getMessage(), message == null ? null : message.getId());
            return false;
        } catch (Exception ex) {
            failed.increment();
            log.warn("[IM 消息镜像] 写 im_platform.im_private_message 失败（不影响 kean 的发送与事务），"
                            + "kean messageId={}, localId={}：{}",
                    message == null ? null : message.getId(),
                    message == null ? null : message.getLocalId(), ex.getMessage());
            return false;
        }
    }

    /**
     * 真正执行一次幂等 UPSERT。**会外抛异常**（由 {@link #mirrorQuietly} 统一兜住），
     * 以便补偿器区分「这条坏了」与「整轮都坏了」。
     *
     * @return {@code true} = SQL 执行成功（含「重复键命中、什么都没改」）
     * @throws MirrorSkipException 这条消息按 box 的列约束不该/不能镜像
     */
    public boolean mirrorPrivate(ChatMessage message, ChatSession session) {
        if (!enabled()) {
            return false;
        }
        Object[] args = buildArgs(message, session);
        attempts.increment();
        int rows = inBoxTransaction(args);
        // rows 语义（JDBC 默认 useAffectedRows=false）：
        //   1 = 新插入；2 = 命中唯一键且 UPDATE 子句确实改了值；0 = 命中唯一键但值没变（MySQL 的快路径）。
        // 我们的 UPDATE 子句是「写回同值」，所以重复补写在不同 MySQL 版本上可能落到 2 或 0 —— 都算成功。
        if (rows == 1) {
            written.increment();
            log.debug("[IM 消息镜像] 已写入 box 影子行：localId={}, seqNo={}, convKey={}",
                    message.getLocalId(), message.getSeqNo(), convKey(session));
        } else {
            duplicates.increment();
            log.debug("[IM 消息镜像] 命中唯一键 (send_id, local_id)，未产生第二行：localId={}, rows={}",
                    message.getLocalId(), rows);
        }
        return true;
    }

    /**
     * 在 {@code REQUIRES_NEW} 的独立事务里执行那一条 UPSERT（失败时只回滚它自己）。
     *
     * <p>这是「box 失败不回滚 kean」在事务层的落点，理由见类注释「失败策略」第 2 条。</p>
     *
     * @return 受影响行数（MySQL 语义见 {@link #mirrorPrivate} 的注释）
     */
    private int inBoxTransaction(Object[] args) {
        return updateInBox(UPSERT_SQL, args);
    }

    /**
     * 在 {@code REQUIRES_NEW} 的独立事务里执行一条写在 {@code im_platform} 上的 SQL。
     *
     * <p>{@link #inBoxTransaction} 与一次性已读对齐（{@link #repairReadStateFromKean()}）共用它：
     * 事务隔离那一层只有一个实现，不会出现「补写有隔离、对齐没有」这种半吊子状态。</p>
     *
     * <p>同一条理由重复一遍（这条最容易想当然）：{@code JdbcTemplate} 用的是主 DataSource，
     * Spring 的 {@code DataSourceUtils} 会优先复用当前线程已绑定的连接 ——
     * 不显式挂起外层事务的话，这条 box 的语句会跑进调用方的 kean 事务里。</p>
     *
     * @return 受影响行数（{@code null} = 事务模板没给出结果，按 0 处理）
     */
    private int updateInBox(String sql, Object... args) {
        TransactionTemplate template = mirrorTemplate;
        if (template == null) {
            // 容器里没有 PlatformTransactionManager：退化成「与调用方共用连接」。
            // 此时异常仍在本类内部被吞掉（第 1 条机制），只是少了事务层的隔离。
            return imPlatformJdbc.update(sql, args);
        }
        Integer rows = template.execute(status -> imPlatformJdbc.update(sql, args));
        return rows == null ? 0 : rows;
    }

    /**
     * 构造与 {@link #UPSERT_SQL} 的 {@code ?} 逐一对应的参数（顺序即列顺序）。
     *
     * <p>抽成一个方法，是为了让「SQL 的列顺序」与「参数顺序」在同一处维护 ——
     * 将来有人加列却忘了加参数时，至少改动点只有一个（与
     * {@link ImShadowUserService#buildUpsertArgs} 同一取舍）。</p>
     *
     * @throws MirrorSkipException 缺 {@code local_id} / {@code seq_no}、
     *                             会话两端推不出 {@code recv_id}、或字符串超列宽
     */
    private Object[] buildArgs(ChatMessage message, ChatSession session) {
        if (message == null || session == null) {
            throw new MirrorSkipException("消息或会话为空");
        }
        Long sendId = message.getSenderId();
        Long recvId = peerId(session, sendId);
        Long seqNo = message.getSeqNo();
        String localId = message.getLocalId() == null ? "" : message.getLocalId().trim();
        if (!StringUtils.hasText(localId)) {
            // box 对外要求 localId 必填（PrivateMessageDTO.@NotEmpty）；box 自己的 HTTP 路径会补
            // IdWorker.getIdStr()。kean 这边理论上也总会补（generatedLocalId），但历史行可能是 NULL ——
            // 那种行不镜像：它没有幂等键，补写时无法判断「是否已存在」。
            throw new MirrorSkipException("kean 侧 local_id 为空，缺幂等键，不镜像");
        }
        if (localId.length() > MAX_LEN_LOCAL_ID) {
            throw new MirrorSkipException("local_id 长度 " + localId.length() + " 超过 box 列宽 " + MAX_LEN_LOCAL_ID
                    + "（截断会破坏幂等键，故拒绝镜像）");
        }
        if (seqNo == null) {
            throw new MirrorSkipException("kean 侧 seq_no 为空（历史行），box 该列 NOT NULL，不镜像");
        }
        if (sendId == null || recvId == null) {
            throw new MirrorSkipException("推不出 send_id / recv_id（会话两端与发送者不匹配），不镜像");
        }
        String key = convKey(session, sendId, recvId);
        if (key.length() > MAX_LEN_CONV_KEY) {
            throw new MirrorSkipException("conv_key 长度 " + key.length() + " 超过 box 列宽 " + MAX_LEN_CONV_KEY);
        }
        LocalDateTime sendTime = message.getCreatedAt() == null ? LocalDateTime.now() : message.getCreatedAt();

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("local_id", localId);
        args.put("seq_no", seqNo.intValue());
        args.put("send_id", sendId);
        args.put("recv_id", recvId);
        args.put("conv_key", key);
        args.put("content", boxContent(message));
        args.put("type", boxMessageType(message.getMsgType()));
        args.put("status", boxStatusOf(message, session, recvId));
        args.put("send_time", Timestamp.valueOf(sendTime));
        return args.values().toArray();
    }

    /**
     * 这条消息在 box 侧应当写入的 {@code status}
     * （{@code 1} = {@link #BOX_STATUS_DELIVERED} 未读 / {@code 3} = {@link #BOX_STATUS_READED} 已读）。
     *
     * <p><b>为什么不能写常量 1</b>：补偿器回填历史消息时，那些消息在 kean 侧<b>早就读过</b>了，
     * 一律写成 1 会让 box 里凭空多出未读 —— 线上实测过一次（24 / 30 条假未读，
     * 切到 box 未读来源后立刻对用户可见），详见类注释「已读状态」小节。</p>
     *
     * <p>判据只有一条，且与 B-3 的 {@link ImUnreadQueryService#markReadInBox} 同源：
     * 「对<b>接收方</b>而言 {@code seq_no <= 接收方在该会话的已读位点}」⇒ 已读。</p>
     *
     * <p>三个边界都按「<b>拿不准就算未读</b>」处理 —— 宁可多留一条未读（next markRead 会清掉，
     * 用户可见且可自愈），也不要凭空把用户没读过的消息标成已读（那是不可自愈的数据损失）：</p>
     * <ul>
     *   <li>{@code seq_no} 为 NULL ⇒ 未读（实际上这种行在 {@link #buildArgs} 里已被 skip，这里只是不放 NPE 出去）；</li>
     *   <li>接收方推不出（不是会话两端）或该侧位点为 NULL ⇒ 未读（位点 NULL = 还没产生过已读位点）；</li>
     *   <li>{@code seq_no > 位点} ⇒ 未读。</li>
     * </ul>
     *
     * @param recvId 接收方（= {@link #peerId(ChatSession, Long)} 的结果，<b>不是</b>发送方）
     */
    private static int boxStatusOf(ChatMessage message, ChatSession session, Long recvId) {
        Long seqNo = message.getSeqNo();
        if (seqNo == null) {
            return BOX_STATUS_DELIVERED;
        }
        Long readSeq = readSeqOf(session, recvId);
        if (readSeq == null) {
            return BOX_STATUS_DELIVERED;
        }
        return seqNo.longValue() <= readSeq.longValue() ? BOX_STATUS_READED : BOX_STATUS_DELIVERED;
    }

    /**
     * 某个用户在会话里的已读位点：{@code user_a_id} → {@code a_read_seq}，
     * {@code user_b_id} → {@code b_read_seq}。
     *
     * <p>返回 {@code null} 的三种情况都表示「读不到位点」⇒ 调用方按未读处理：
     * 会话为空、用户为空、或该用户不是这个会话的两端之一。</p>
     */
    private static Long readSeqOf(ChatSession session, Long userId) {
        if (session == null || userId == null) {
            return null;
        }
        if (userId.equals(session.getUserAId())) {
            return session.getAReadSeq();
        }
        if (userId.equals(session.getUserBId())) {
            return session.getBReadSeq();
        }
        return null;
    }

    /**
     * kean {@code chat_message.content} → box {@code im_private_message.content}。
     *
     * <p><b>原样传递</b>（TEXT 是纯文本，IMAGE 是对象存储 objectKey）。为什么不给图片套 JSON：
     * 见类注释「content 与图片」小节 —— 图像 content 的确切 JSON 形状在本项目里仍是未证实项（U2），
     * 而队列镜像已在传裸 objectKey，两条通道必须一致。D1 阶段只改这一个方法即可。</p>
     */
    private static String boxContent(ChatMessage message) {
        return message.getContent() == null ? "" : message.getContent();
    }

    /**
     * kean 的字符串消息类型 → box {@code MessageType} 数字码。
     *
     * <p>与 {@link ImSenderService} 的队列镜像<b>同一套映射</b>（TEXT=0 / IMAGE=1，未知值回落 TEXT）：
     * 两条通道给 box 的 {@code type} 必须一致，否则同一个 type 在队列里和表里会不一样。</p>
     */
    private static int boxMessageType(String msgType) {
        return "IMAGE".equalsIgnoreCase(msgType == null ? "" : msgType.trim()) ? BOX_TYPE_IMAGE : BOX_TYPE_TEXT;
    }

    /**
     * box 的会话键：{@code Math.min(a, b) + "_" + Math.max(a, b)}。
     *
     * <p>✅ <b>已逐字核对 box 源码</b> {@code com.bx.implatform.util.ConvUtil.buildConvKey}
     * （{@code im-platform-migration.md} §12 的 U3）：
     * {@code return Math.min(userId1, userId2) + "_" + Math.max(userId1, userId2);}。
     * box 侧与 {@code conv_key} 相关的三处（写消息、拉历史、已读回执）全部走这个方法。</p>
     */
    static String convKey(Long userId1, Long userId2) {
        long min = Math.min(userId1, userId2);
        long max = Math.max(userId1, userId2);
        return min + "_" + max;
    }

    /** 会话键（用会话两端；kean 的 {@code chat_session} 本来就保证 {@code user_a_id < user_b_id}）。 */
    private static String convKey(ChatSession session) {
        return convKey(session.getUserAId(), session.getUserBId());
    }

    /** 会话键（用会话两端，顺带断言发送者确实是会话的一方）。 */
    private static String convKey(ChatSession session, Long sendId, Long recvId) {
        String fromSession = convKey(session);
        String fromPair = convKey(sendId, recvId);
        if (!fromSession.equals(fromPair)) {
            // 会话表与消息的 sender 对不上：不做猜测，宁可 skip（有 DEBUG 日志）。
            throw new MirrorSkipException("会话两端(" + session.getUserAId() + "," + session.getUserBId()
                    + ")与 send/recv(" + sendId + "," + recvId + ")不一致");
        }
        return fromSession;
    }

    /** 会话里的另一方（{@code im_private_message.recv_id} 的来源；kean 的消息表没有 recv_id 列）。 */
    private static Long peerId(ChatSession session, Long senderId) {
        if (senderId == null) {
            return null;
        }
        if (senderId.equals(session.getUserAId())) {
            return session.getUserBId();
        }
        if (senderId.equals(session.getUserBId())) {
            return session.getUserAId();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // ② 补偿器（默认关闭）
    // ------------------------------------------------------------------

    /**
     * 补偿一轮：把「kean 有、box 没有」的最近消息幂等补写进 box。
     *
     * <p>周期 {@code kean.im.message-mirror-compensate-interval-ms}（默认 300000 = 5 分钟），
     * 用 {@code fixedDelay}（上一轮结束再等，慢轮不会堆积）。
     * {@code @SchedulerLock} 与 {@code TaskScheduleService} / {@code ImQueueMonitorService}
     * 共用同一套锁：多实例部署时同一时刻只有一个实例真正补写。
     * 注意锁名不是占位符化的（{@code @SchedulerLock.name} 需要编译期常量），
     * 周期变了要改锁名只能改代码 —— 这是可接受的，锁名只是去重标识。</p>
     *
     * <p>整段包 try/catch：补偿是旁路，任何异常都只记日志，绝不影响业务线程。</p>
     */
    @Scheduled(fixedDelayString = "${kean.im.message-mirror-compensate-interval-ms:300000}")
    @SchedulerLock(name = "im.messageMirrorCompensate", lockAtMostFor = "PT5M")
    public void compensate() {
        try {
            if (!compensateReady()) {
                return;
            }
            runCompensateRound();
        } catch (Exception ex) {
            log.warn("[IM 消息镜像] 补偿本轮异常（旁路，不影响任何业务），下一轮重试：{}", ex.getMessage());
        }
    }

    /** 补偿的单轮实现（可被测试/人工触发直接调用；不做开关判断）。 */
    void runCompensateRound() {
        LocalDateTime since = LocalDateTime.now().minusDays(compensateDays);
        List<ChatMessage> candidates = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .isNotNull(ChatMessage::getLocalId)
                .ne(ChatMessage::getLocalId, "")
                .ge(ChatMessage::getCreatedAt, since)
                .orderByDesc(ChatMessage::getId)
                .last("LIMIT " + compensateLimit));
        if (candidates.isEmpty()) {
            log.debug("[IM 消息镜像] 补偿：最近 {} 天内没有候选消息（窗口起点 {}）", compensateDays, since);
            return;
        }
        Map<Long, ChatSession> sessions = loadSessions(candidates);
        List<ChatMessage> missing = filterMissingInBox(candidates, sessions);
        if (missing.isEmpty()) {
            log.debug("[IM 消息镜像] 补偿：候选 {} 条，box 侧全部已存在，无需补写", candidates.size());
            return;
        }
        int ok = 0;
        int fail = 0;
        for (ChatMessage message : missing) {
            ChatSession session = sessions.get(message.getSessionId());
            try {
                if (mirrorPrivate(message, session)) {
                    ok++;
                }
            } catch (MirrorSkipException skip) {
                log.debug("[IM 消息镜像] 补偿跳过 kean messageId={}：{}", message.getId(), skip.getMessage());
            } catch (Exception ex) {
                fail++;
                log.warn("[IM 消息镜像] 补偿补写失败（下一轮会重试，最多重复到成功为止）kean messageId={}：{}",
                        message.getId(), ex.getMessage());
            }
        }
        log.info("[IM 消息镜像] 补偿完成：候选 {} 条 → box 缺 {} 条 → 补写成功 {} 条 / 失败 {} 条"
                        + "（窗口 {} 天，单轮上限 {} 条；累计计数 attempts/written/duplicate/failed/skipped={}）",
                candidates.size(), missing.size(), ok, fail, compensateDays, compensateLimit, statsSnapshot());
        if (fail > failureAlertThreshold) {
            alertCompensateFailure(fail, missing.size(), ok);
        }
    }

    /**
     * 从 kean 侧候选里挑出「box 侧还没有同行」的那些。
     *
     * <p>按 {@code (send_id, local_id)} 判存在性 —— 这正是唯一索引的两列，
     * 因此「查不到」与「插得进去」是同一个判据（不存在并发窗口）：
     * 即使两次补写并发，第二次也会命中唯一键而不是产生第二行。</p>
     */
    private List<ChatMessage> filterMissingInBox(List<ChatMessage> candidates, Map<Long, ChatSession> sessions) {
        List<ChatMessage> queryable = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (ChatMessage message : candidates) {
            if (isQueryable(message, sessions)) {
                queryable.add(message);
                keys.add(existsKey(message.getSenderId(), message.getLocalId().trim()));
            }
        }
        if (keys.isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> existing = queryExisting(keys);
        List<ChatMessage> missing = new ArrayList<>();
        for (ChatMessage message : queryable) {
            if (!existing.contains(existsKey(message.getSenderId(), message.getLocalId().trim()))) {
                missing.add(message);
            }
        }
        return missing;
    }

    /** 参与「是否存在」判定的最小条件：有会话、有发送者、有幂等键（其余交给 {@link #buildArgs} 校验）。 */
    private static boolean isQueryable(ChatMessage message, Map<Long, ChatSession> sessions) {
        return sessions.get(message.getSessionId()) != null
                && message.getSenderId() != null
                && StringUtils.hasText(message.getLocalId());
    }

    /** 存在性判据的字符串化键（内存集合用；真实判据始终是 box 表上的唯一索引）。 */
    private static String existsKey(Long sendId, String localId) {
        return sendId + "\u0000" + localId;
    }

    /**
     * 批量查 box 侧已存在的 {@code (send_id, local_id)}。
     *
     * <p>写法说明（避免踩坑）：{@code send_id IN (...) AND local_id IN (...)} 会返回<b>笛卡尔积</b>里
     * 真实存在的行 —— 这在语义上是对的（{@code (send_id, local_id)} 是唯一键，
     * 命中「不属于同一对」的组合本来就不存在），但返回行数有上界风险，
     * 所以按 {@link #EXISTS_QUERY_CHUNK} 分批、每批各自一个查询。</p>
     *
     * <p>为什么不用跨库 JOIN：{@code Kean.chat_message} 与 {@code im_platform.im_private_message}
     * 是两个 schema。虽然同一个 MySQL 实例下 {@code Kean.chat_message c JOIN im_platform...}
     * 通常可行，但那会把「两个库的排序规则/权限」变成新的隐式依赖；
     * 这里刻意只对 box 的表发查询，跨库对比放在内存里做。</p>
     */
    private Set<String> queryExisting(Set<String> keys) {
        Set<String> existing = new LinkedHashSet<>();
        List<String> batch = new ArrayList<>(EXISTS_QUERY_CHUNK);
        for (String key : keys) {
            batch.add(key);
            if (batch.size() >= EXISTS_QUERY_CHUNK) {
                queryExistingChunk(batch, existing);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            queryExistingChunk(batch, existing);
        }
        return existing;
    }

    private void queryExistingChunk(List<String> chunk, Set<String> collector) {
        List<Long> sendIds = new ArrayList<>(chunk.size());
        List<String> localIds = new ArrayList<>(chunk.size());
        for (String key : chunk) {
            int split = key.indexOf('\u0000');
            sendIds.add(Long.valueOf(key.substring(0, split)));
            localIds.add(key.substring(split + 1));
        }
        String sql = "SELECT send_id, local_id FROM " + TABLE
                + " WHERE send_id IN (" + inPlaceholders(sendIds.size()) + ")"
                + " AND local_id IN (" + inPlaceholders(localIds.size()) + ")";
        List<Object> args = new ArrayList<>(sendIds.size() + localIds.size());
        args.addAll(sendIds);
        args.addAll(localIds);
        for (Map<String, Object> row : imPlatformJdbc.queryForList(sql, args.toArray(new Object[0]))) {
            Object sendId = row.get("send_id");
            Object localId = row.get("local_id");
            if (sendId != null && localId != null) {
                collector.add(existsKey(Long.valueOf(sendId.toString()), localId.toString()));
            }
        }
    }

    /** 生成 {@code ?,?,?} 形式的占位符串（列数由调用方保证与参数个数一致）。 */
    private static String inPlaceholders(int count) {
        StringBuilder sb = new StringBuilder(count * 2);
        for (int i = 0; i < count; i++) {
            sb.append(i == 0 ? "?" : ",?");
        }
        return sb.toString();
    }

    /** 批量取会话（一次查询，不按消息逐条查库）。 */
    private Map<Long, ChatSession> loadSessions(List<ChatMessage> messages) {
        Set<Long> ids = messages.stream()
                .map(ChatMessage::getSessionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, ChatSession> result = new HashMap<>();
        for (ChatSession session : chatSessionMapper.selectBatchIds(ids)) {
            result.put(session.getId(), session);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // ③ 比对（只读，默认关闭；只报警不修数据）
    // ------------------------------------------------------------------

    /**
     * 比对一轮（<b>只读</b>）：最近 N 天内，每个活跃会话在 kean 与 box 的条数是否一致。
     *
     * <p>周期 {@code kean.im.message-mirror-compare-interval-ms}（默认 1800000 = 30 分钟）。
     * 不一致只发告警（复用 {@link ImAlertService} 的冷却），<b>绝不改数据</b> ——
     * 修复只由上面的补偿器做。这是刻意的分工：比对是观测，补偿是动作。</p>
     *
     * <p>⚠️ 本轮仍然全在 {@code im_platform.im_private_message} 上做只读 SQL，
     * <b>不读 box 的 Redis 位点</b>（那些键的口径归阶段 C，本轮不碰）。</p>
     *
     * <h3>⚠️ 它的两个已知局限（刻意不做，先记下来）</h3>
     * <ol>
     *   <li><b>只比「条数」，不比「内容」</b>。条数一致但某一行的 {@code seq_no} / {@code content} 不同，
     *       本比对<b>看不出来</b>。逐行比对（{@code im-platform-migration.md} §3.B.1 第 2 条那条 SQL）
     *       请按文档手工执行，因为它是放量前的关键验收，不该被一个定时任务悄悄「通过」掉；</li>
     *   <li><b>窗口内若 box 侧本来就有它自己的消息</b>（box 在阶段 B 之前被真实使用过、
     *       或有人直接调过 box 的 {@code /private/message}），那么该 {@code conv_key} 的 box 条数
     *       会天然大于 kean，比对会报假警。阶段 B 的前提本就是「box 只被写、没人读」，
     *       真出现这种情况应当先查清「谁在写 box」，而不是把阈值调大。</li>
     * </ol>
     */
    @Scheduled(fixedDelayString = "${kean.im.message-mirror-compare-interval-ms:1800000}")
    @SchedulerLock(name = "im.messageMirrorCompare", lockAtMostFor = "PT5M")
    public void compare() {
        try {
            if (!compareReady()) {
                return;
            }
            runCompareRound();
        } catch (Exception ex) {
            log.warn("[IM 消息镜像] 比对本轮异常（只读旁路，不影响任何业务），下一轮重试：{}", ex.getMessage());
        }
    }

    /** 比对的单轮实现（可被测试/人工触发直接调用；不做开关判断）。 */
    void runCompareRound() {
        LocalDateTime since = LocalDateTime.now().minusDays(compareDays);
        List<ChatSession> sessions = chatSessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                .ge(ChatSession::getUpdatedAt, since)
                .orderByDesc(ChatSession::getUpdatedAt)
                .last("LIMIT " + compareLimit));
        if (sessions.isEmpty()) {
            log.debug("[IM 消息镜像] 比对：最近 {} 天内没有活跃会话（窗口起点 {}）", compareDays, since);
            return;
        }
        Map<Long, String> convKeys = new LinkedHashMap<>();
        Map<String, Long> keyToSession = new HashMap<>();
        for (ChatSession session : sessions) {
            if (session.getUserAId() == null || session.getUserBId() == null) {
                continue;
            }
            String key = convKey(session);
            convKeys.put(session.getId(), key);
            keyToSession.put(key, session.getId());
        }
        if (convKeys.isEmpty()) {
            return;
        }
        Map<String, Long> boxCounts = countBox(keyToSession.keySet(), since);
        Map<Long, Long> keanCounts = countKean(convKeys.keySet(), since);

        List<String> mismatches = new ArrayList<>();
        long keanTotal = 0L;
        long boxTotal = 0L;
        for (Map.Entry<Long, String> entry : convKeys.entrySet()) {
            long kean = keanCounts.getOrDefault(entry.getKey(), 0L);
            long box = boxCounts.getOrDefault(entry.getValue(), 0L);
            keanTotal += kean;
            boxTotal += box;
            long diff = kean - box;
            if (Math.abs(diff) >= compareDiffThreshold) {
                mismatches.add("sessionId=" + entry.getKey() + " convKey=" + entry.getValue()
                        + " kean=" + kean + " box=" + box + " 差=" + diff);
            }
        }
        if (mismatches.isEmpty()) {
            log.debug("[IM 消息镜像] 比对一致：会话 {} 个，kean {} 条 / box {} 条（窗口 {} 天内）",
                    convKeys.size(), keanTotal, boxTotal, compareDays);
        } else {
            log.warn("[IM 消息镜像] 比对发现 {} 个会话条数不一致（kean {} 条 / box {} 条，窗口 {} 天内）：{}",
                    mismatches.size(), keanTotal, boxTotal, compareDays, mismatches);
            alertCompareMismatch(mismatches, keanTotal, boxTotal, since);
        }

        // 阶段 B-3：追加一项「未读数比对」（同样只读、只报不修，跟随同一个 compare 开关）。
        // 放在条数比对【之后】且互不影响：条数比对报的是「消息有没有写过去」，
        // 未读比对报的是「已读投影有没有落下去」，两者可以是独立的故障。
        compareUnread(sessions, keyToSession, since, convKeys);
    }

    /**
     * 阶段 B-3 的未读数比对（<b>只读</b>，与条数比对共用
     * {@link #compareReady()} 与 {@link #compareIntervalMs}，<b>不新增开关</b>）。
     *
     * <p>对最近活跃的每个会话、<b>双方各算一次</b>：</p>
     * <ul>
     *   <li>kean 侧：{@code chat_session.a_unread / b_unread}
     *       （换来源后它<b>仍然在写</b>，只是不再作为展示来源）；</li>
     *   <li>box 侧：{@code SELECT COUNT(*) WHERE recv_id=? AND conv_key=? AND status<3}
     *       —— 与接口切换后<b>同一套 SQL</b>（{@link ImUnreadQueryService}），
     *       这样「告警说的」与「用户看到的」不可能出现第二套口径。</li>
     * </ul>
     *
     * <p>⚠️ 本轮开这个开关的操作含义要写清：它<b>只报告</b>，既不修 kean 计数器，
     * 也不改 box 的 status。真正的收敛手段有两个，都靠日志/告警指出方向：
     * 把 {@code kean.im.unread-source} 设回 {@code kean}（回退），
     * 或让用户重新打开会话触发一次 {@code markRead}（重放投影）。</p>
     */
    private void compareUnread(List<ChatSession> sessions, Map<String, Long> keyToSession, LocalDateTime since,
                               Map<Long, String> convKeys) {
        // 未读来源没切到 box（默认 kean）时，本项无意义（也拿不到 box 侧的读数）：
        // 直接跳过，避免在默认配置下每轮多发两次 SQL、并刷一行「未读比对跳过」。
        if (!imUnreadQueryService.active()) {
            log.debug("[IM 消息镜像] 未读比对跳过：未读来源未切到 box（{}={}）",
                    ImUnreadQueryService.PROPERTY_UNREAD_SOURCE, imUnreadQueryService.configuredSource());
            return;
        }
        Set<Long> userIds = new LinkedHashSet<>();
        for (ChatSession session : sessions) {
            if (session.getUserAId() != null) {
                userIds.add(session.getUserAId());
            }
            if (session.getUserBId() != null) {
                userIds.add(session.getUserBId());
            }
        }
        // 未读是「按人」的：每人一条 GROUP BY SQL（最多两条），不按会话逐条查。
        Map<Long, Map<String, Long>> boxUnreadByUser = new LinkedHashMap<>();
        for (Long userId : userIds) {
            Map<String, Long> counts = imUnreadQueryService.unreadCountsByConvKey(userId, keyToSession.keySet());
            if (counts == null) {
                log.warn("[IM 消息镜像] 未读比对跳过：从 box 读未读数失败（只影响本次观测，不影响业务）");
                return;
            }
            boxUnreadByUser.put(userId, counts);
        }
        List<String> mismatches = new ArrayList<>();
        long boxUnreadTotal = 0L;
        for (ChatSession session : sessions) {
            String key = convKeys.get(session.getId());
            if (key == null) {
                continue;
            }
            boxUnreadTotal += compareUnreadOf(session, session.getUserAId(), key, boxUnreadByUser, mismatches);
            boxUnreadTotal += compareUnreadOf(session, session.getUserBId(), key, boxUnreadByUser, mismatches);
        }
        if (mismatches.isEmpty()) {
            // 两侧都相等是常态：只留 DEBUG，避免每 30 分钟刷一行 INFO。
            log.debug("[IM 消息镜像] 未读比对一致：会话 {} 个（box 侧未读合计 {} 条，窗口 {} 天内）",
                    convKeys.size(), boxUnreadTotal, compareDays);
            return;
        }
        log.warn("[IM 消息镜像] 未读比对发现 {} 处不一致（box 侧未读合计 {} 条，窗口 {} 天内；"
                        + "未读来源 {}={}）：{}",
                mismatches.size(), boxUnreadTotal, compareDays,
                ImUnreadQueryService.PROPERTY_UNREAD_SOURCE, imUnreadQueryService.configuredSource(), mismatches);
        alertCompareUnreadMismatch(mismatches, boxUnreadTotal, since);
    }

    /**
     * 比对「一个会话 × 一个人」的未读数，不一致时追加一行明细。
     *
     * <p>返回 box 侧的未读数（用于合计），便于调用方一行搞定累加。</p>
     *
     * <p>明细里刻意带上「我读到的位点 / 会话最大序号 / 对方未读」三个上下文：
     * 只看两个数字无法区分「用户还没读」与「已读投影丢了」，而这三个值能直接指出是哪一种
     * （{@code readSeq < lastSeqNo} ⇒ 大概率只是还没读；{@code readSeq == lastSeqNo} 而 box 仍 &gt; 0
     * ⇒ 投影或双写丢了）。</p>
     */
    private long compareUnreadOf(ChatSession session, Long userId, String convKey,
                                 Map<Long, Map<String, Long>> boxUnreadByUser, List<String> mismatches) {
        if (userId == null) {
            return 0L;
        }
        Map<String, Long> perUser = boxUnreadByUser.get(userId);
        long boxUnread = perUser == null ? 0L : perUser.getOrDefault(convKey, 0L);
        boolean userIsA = userId.equals(session.getUserAId());
        long keanUnread = keanUnreadOf(session, userIsA);
        if (keanUnread == boxUnread) {
            return boxUnread;
        }
        mismatches.add("sessionId=" + session.getId() + " convKey=" + convKey + " userId=" + userId
                + " 侧=" + (userIsA ? "a" : "b")
                + " kean未读=" + keanUnread + " box未读=" + boxUnread + " 差=" + (keanUnread - boxUnread)
                + "（我读到位点=" + (userIsA ? session.getAReadSeq() : session.getBReadSeq())
                + " 会话最大序号=" + session.getLastSeqNo()
                + " 对方未读=" + keanUnreadOf(session, !userIsA) + "）");
        return boxUnread;
    }

    /** kean 侧某一方在该会话上的未读计数器（{@code user_a_id} → {@code a_unread}，否则 {@code b_unread}）。 */
    private static long keanUnreadOf(ChatSession session, boolean userIsA) {
        Integer unread = userIsA ? session.getAUnread() : session.getBUnread();
        return unread == null ? 0L : unread;
    }

    /** box 侧每个 {@code conv_key} 在窗口内的条数（只读）。 */
    private Map<String, Long> countBox(Set<String> convKeys, LocalDateTime since) {
        List<String> keys = new ArrayList<>(convKeys);
        String sql = "SELECT conv_key, COUNT(*) AS cnt FROM " + TABLE
                + " WHERE conv_key IN (" + inPlaceholders(keys.size()) + ") AND send_time >= ? GROUP BY conv_key";
        List<Object> args = new ArrayList<>(keys.size() + 1);
        args.addAll(keys);
        args.add(Timestamp.valueOf(since));
        Map<String, Long> counts = new HashMap<>();
        for (Map<String, Object> row : imPlatformJdbc.queryForList(sql, args.toArray(new Object[0]))) {
            Object key = row.get("conv_key");
            Object cnt = row.get("cnt");
            if (key != null && cnt != null) {
                counts.put(key.toString(), Long.valueOf(cnt.toString()));
            }
        }
        return counts;
    }

    /** kean 侧每个会话在窗口内的条数（只读，逻辑删除的不算）。 */
    private Map<Long, Long> countKean(Set<Long> sessionIds, LocalDateTime since) {
        Map<Long, Long> counts = new HashMap<>();
        if (sessionIds.isEmpty()) {
            return counts;
        }
        List<ChatMessage> messages = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .in(ChatMessage::getSessionId, sessionIds)
                .ge(ChatMessage::getCreatedAt, since)
                .select(ChatMessage::getSessionId));
        for (ChatMessage message : messages) {
            counts.merge(message.getSessionId(), 1L, Long::sum);
        }
        return counts;
    }

    // ------------------------------------------------------------------
    // 告警正文（复用 ImAlertService：同一套冷却、收件人回落、永不外抛）
    // ------------------------------------------------------------------

    private void alertCompensateFailure(int fail, int missing, int ok) {        imAlertService.alert(
                KIND_MESSAGE_MIRROR_FAILURE,
                "ERROR",
                "IM 消息镜像补写失败次数超阈值（box 的 im_platform.im_private_message 可能不可写）",
                "本轮补写失败 " + fail + " 条（成功 " + ok + " 条 / box 缺 " + missing + " 条）",
                "单轮失败 > " + failureAlertThreshold + " 条（kean.im.message-mirror-failure-threshold）",
                List.of(TABLE, "im_platform"),
                "【课安 IM 告警】消息镜像补写失败超阈值（ERROR）",
                compensateFailureBody(fail, missing, ok));
    }

    private String compensateFailureBody(int fail, int missing, int ok) {
        return """
                【课安 IM 告警】消息镜像补写失败

                问题类型：kean → box 的消息双写（阶段 B-1）在补写环节失败过多
                当前值：本轮 candidate 中 box 缺 %d 条，补写成功 %d 条、失败 %d 条
                阈值：单轮失败 > %d 条（配置项 kean.im.message-mirror-failure-threshold）
                补偿周期：%d ms（配置项 kean.im.message-mirror-compensate-interval-ms）
                补偿窗口：最近 %d 天；单轮上限 %d 条

                背景：kean 是主，box 是影子。发送时写 box 失败【不会】回滚 kean 的消息
                （这是刻意的：box 抖一下不能让用户发不出消息），所以这类失败只会出现在
                本告警与 kean 日志里，然后由补偿器在下一轮重试（按 (send_id, local_id) 幂等，不会重复）。

                排查顺序：
                  1) box 的库是否可写：SHOW TABLES FROM im_platform LIKE 'im_private_message';
                  2) 当前数据库账号是否仍有 im_platform.* 权限（GRANT 没生效 / 被收回）
                  3) 表结构是否被改过（列宽 / NOT NULL 约束变化会让 INSERT 直接失败）；
                     唯一索引是否还在：SHOW INDEX FROM im_platform.im_private_message;
                  4) 是否只是宿主/MySQL 抖动（是的话下一轮成功数会回升）
                  5) kean 日志关键字：grep "\\[IM 消息镜像\\]" <kean 日志文件> | tail -50

                累计计数（attempts/written/duplicate/failed/skipped）：%s
                说明：本邮件由 kean 定时补偿自动发出；同一类告警 %d 分钟内只发一次（冷却期内只记日志）。
                """.formatted(fail, ok, missing, failureAlertThreshold, compensateIntervalMs,
                compensateDays, compensateLimit, statsSnapshot(), imAlertService.cooldownMinutes());
    }

    private void alertCompareMismatch(List<String> mismatches, long keanTotal, long boxTotal, LocalDateTime since) {
        imAlertService.alert(
                KIND_MESSAGE_MIRROR_MISMATCH,
                "WARN",
                "IM 消息镜像条数比对不一致（kean 与 box 的影子表对不上）",
                "不一致会话 " + mismatches.size() + " 个（kean 合计 " + keanTotal + " 条 / box 合计 " + boxTotal + " 条）",
                "差额 >= " + compareDiffThreshold + " 条即报（kean.im.message-mirror-compare-diff-threshold）",
                List.of(TABLE),
                "【课安 IM 告警】消息镜像条数不一致（WARN）",
                compareMismatchBody(mismatches, keanTotal, boxTotal, since));
    }

    private String compareMismatchBody(List<String> mismatches, long keanTotal, long boxTotal, LocalDateTime since) {
        String sample = mismatches.size() > 20
                ? String.join("\n", mismatches.subList(0, 20)) + "\n...（仅显示前 20 个）"
                : String.join("\n", mismatches);
        return """
                【课安 IM 告警】消息镜像条数比对不一致

                问题类型：最近 %d 天内，kean.chat_message 与 box im_platform.im_private_message 的条数对不上
                当前值：不一致会话 %d 个；kean 合计 %d 条 / box 合计 %d 条
                比对窗口起点：%s
                阈值：差额 >= %d 条（kean.im.message-mirror-compare-diff-threshold）
                比对周期：%d ms；单轮最多比对 %d 个会话（配置项 kean.im.message-mirror-compare-*）

                明细（kean 为「正常未删除且 local_id 非空」的条数）：
                %s

                可能原因（按概率排序）：
                  ① 主写开关刚打开 / 刚关过 —— 窗口内有一段消息本来就没双写（预期内，不需要处理）；
                  ② box 侧写失败但补偿器还没跑到，或补偿器没开
                     （kean.im.message-mirror-compensate-enabled，默认 false）；
                  ③ 某条消息的数据本身不满足 box 的列约束而被 skip（kean 日志 DEBUG：
                     "[IM 消息镜像] 跳过"）——最常见的是 local_id 超过 varchar(32)；
                  ④ box 侧有人/有程序删过行（阶段 B 期间本不该有人读，更不该有人删）。

                说明：比对【只报告、绝不改数据】，修复由补偿器负责（它是幂等的）。
                本邮件由 kean 定时比对自动发出；同一类告警 %d 分钟内只发一次（冷却期内只记日志）。
                """.formatted(compareDays, mismatches.size(), keanTotal, boxTotal, since,
                compareDiffThreshold, compareIntervalMs, compareLimit, sample, imAlertService.cooldownMinutes());
    }

    /**
     * 阶段 B-3：未读数不一致的告警（与条数比对<b>分属两个冷却类别</b>，见
     * {@link #KIND_MESSAGE_MIRROR_UNREAD_MISMATCH}）。
     */
    private void alertCompareUnreadMismatch(List<String> mismatches, long boxUnreadTotal, LocalDateTime since) {
        imAlertService.alert(
                KIND_MESSAGE_MIRROR_UNREAD_MISMATCH,
                "WARN",
                "IM 未读数比对不一致（kean 的 a_unread/b_unread 与 box 消息 status 推导值对不上）",
                "不一致 " + mismatches.size() + " 处（box 侧未读合计 " + boxUnreadTotal + " 条）",
                "任何差额即报；未读来源 kean.im.unread-source="
                        + imUnreadQueryService.configuredSource()
                        + "（box 未读 = 该会话中 recv_id=本人 且 status < " + BOX_STATUS_READED + " 的条数）",
                List.of(TABLE, "Kean.chat_session"),
                "【课安 IM 告警】未读数比对不一致（WARN）",
                compareUnreadMismatchBody(mismatches, boxUnreadTotal, since));
    }

    private String compareUnreadMismatchBody(List<String> mismatches, long boxUnreadTotal, LocalDateTime since) {
        String sample = mismatches.size() > 20
                ? String.join("\n", mismatches.subList(0, 20)) + "\n...（仅显示前 20 个）"
                : String.join("\n", mismatches);
        return """
                【课安 IM 告警】未读数比对不一致

                问题类型：最近 %d 天内，kean 的会话计数器与 box 消息 status 推导出的未读数对不上
                当前值：不一致 %d 处；box 侧未读合计 %d 条
                比对窗口起点：%s
                比对周期：%d ms；单轮最多比对 %d 个会话（配置项 kean.im.message-mirror-compare-*，与条数比对同一开关）
                未读来源：kean.im.unread-source=%s（box 则是 %s）
                  · kean 的读法：chat_session.a_unread（user_a_id 一方）/ b_unread（user_b_id 一方）
                  · box  的读法：SELECT COUNT(*) FROM im_platform.im_private_message
                                  WHERE recv_id = 本人 AND conv_key = 该会话 AND status < 3

                明细（差 = kean未读 - box未读；「我读到位点」是 chat_session.a_read_seq/b_read_seq）：
                %s

                可能原因（按概率排序）：
                  ① 已读回写失败后没重放：用户点开会话时 box 的 UPDATE 失败（kean 日志 WARN
                     "[IM 未读来源] box 已读回写失败"）⇒ box 仍算未读、kean 计数器已清零。
                     让该用户重新打开一次会话即可自愈（同一条 UPDATE 幂等）；
                  ② 双写没开或刚打开：box 里压根没有最近的消息，推导出的未读偏小
                     （kean.im.message-mirror-enabled，属可预期，不是故障）；
                  ③ 双写时该消息被 skip 或补偿器没跑到（见条数比对那一类告警，优先看它）；
                  ④ kean 的计数器自身被历史脏数据带偏（例如回退前没清零的旧值）。

                注意：「我读到位点 < 会话最大序号」通常只是『用户确实还没读』（正常）；
                而「位点已到最大序号、box 却仍 > 0」才是投影/双写丢了的信号。

                说明：比对【只报告、绝不改数据】。收敛手段只有两个：
                  · 应急回退：把 kean.im.unread-source 设回 kean 并重启 kean
                    （kean 侧的 a_unread/b_unread 一直在维护，回退后立刻正确）；
                  · 自愈：让相应用户重新打开该会话，触发一次 markRead 重放。
                本邮件由 kean 定时比对自动发出；同一类告警 %d 分钟内只发一次（冷却期内只记日志）。
                """.formatted(compareDays, mismatches.size(), boxUnreadTotal, since,
                compareIntervalMs, compareLimit, imUnreadQueryService.configuredSource(),
                ImUnreadQueryService.SOURCE_BOX, sample, imAlertService.cooldownMinutes());
    }

    // ------------------------------------------------------------------
    // ④ 一次性已读对齐（默认关闭；只在启动时跑一次，方向只有「未读 → 已读」）
    // ------------------------------------------------------------------

    /**
     * 启动时的一次性已读对齐（<b>默认关闭</b>，开关
     * {@code kean.im.message-mirror-repair-read-state-enabled}，默认 false）。
     *
     * <p>它存在的唯一理由：本类早期版本回填历史消息时把 {@code status} 无条件写成 1，
     * 于是 box 里<b>凭空多出未读</b>。这条 UPDATE 把「kean 侧位点已经读到、box 却还算未读」的行
     * 一次性对齐（等价于运维手工在服务器上执行下面这条 SQL，只是按会话批量化、有窗口上限）：</p>
     * <pre>
     * UPDATE im_platform.im_private_message SET status = 3
     *  WHERE recv_id = &lt;本人&gt; AND conv_key = '&lt;min_max&gt;' AND seq_no &lt;= &lt;本人已读位点&gt; AND status &lt; 3;
     * </pre>
     *
     * <p>三条性质（都是为了「跑错也不会更糟」）：</p>
     * <ol>
     *   <li><b>幂等</b>：{@code AND status < 3} ⇒ 已经对齐过的行再次执行是 0 行；</li>
     *   <li><b>绝不反向</b>：只有 {@code SET status = 3}，永远不会把已读改回未读
     *       （对比对器报的「box 未读偏大」这一类是修复，对「box 未读偏小」无副作用）；</li>
     *   <li><b>不碰 kean</b>：只对 {@code im_platform} 发 UPDATE，读 kean 的会话位点走
     *       {@link ChatSessionMapper}（<b>不做跨库 JOIN</b>，与 {@link #queryExisting} 同一取舍）。</li>
     * </ol>
     *
     * <p>⚠️ 为什么必须是「一次性 + 默认关闭」而不是定时任务：它是<b>历史数据的补救</b>，
     * 正常情况下 0 行；如果它变成常驻定时任务，就等于给「回填算错状态」这类 bug 加了一个
     * 掩盖装置 —— 那会让真正的根因（见类注释「已读状态」）一直藏在水下。</p>
     *
     * <p>⚠️ 它复用的是主写通道（{@code imPlatformJdbc}），因此<b>必须先打开主写开关</b>；
     * 两者都开时启动日志会明确写出「实际会执行=true」。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void repairReadStateOnStartup() {
        if (!repairReadStateReady()) {
            // 默认路径：一行 SQL 都不发（构造函数里已经打过「实际会执行=false」的 INFO）。
            log.debug("[IM 消息镜像] 一次性已读对齐未启用（repairReadStateEnabled={}, 主写生效={}），跳过",
                    repairReadStateEnabled, enabled());
            return;
        }
        try {
            repairReadStateFromKean();
        } catch (Exception ex) {
            // 与补偿器同一原则：这是旁路，任何异常都只记日志，绝不影响启动与业务线程。
            log.warn("[IM 消息镜像] 一次性已读对齐执行异常（不影响启动与业务；本轮已中止）：{}", ex.getMessage());
        }
    }

    /**
     * 一次性已读对齐的单轮实现（可被人工触发/测试直接调用；<b>不做开关判断</b>）。
     *
     * <p>扫描「最近 {@code repairReadStateDays} 天内更新过」的会话（上限复用
     * {@code kean.im.message-mirror-compensate-limit} 个），对会话两端各自执行一次
     * {@link #REPAIR_READ_STATE_SQL}（位点为 NULL 或 ≤ 0 的一方跳过：没有可对齐的东西）。</p>
     *
     * <p>⚠️ 逐会话 try/catch：一条坏会话不会让整轮停下（对齐是补救动作，做多少算多少），
     * 失败数在结尾的 INFO 里给出。</p>
     *
     * @return 被改成已读的<b>行数</b>（0 = 无可对齐或已经对齐过，都是正常结果）
     */
    int repairReadStateFromKean() {
        if (!enabled()) {
            log.warn("[IM 消息镜像] 一次性已读对齐被调用，但主写通道不可用（主写开关未生效或无 DataSource）"
                    + "⇒ 不执行任何 UPDATE");
            return 0;
        }
        LocalDateTime since = LocalDateTime.now().minusDays(repairReadStateDays);
        List<ChatSession> sessions = chatSessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                .ge(ChatSession::getUpdatedAt, since)
                .orderByDesc(ChatSession::getUpdatedAt)
                .last("LIMIT " + compensateLimit));
        int aligned = 0;
        int touched = 0;
        int failed = 0;
        for (ChatSession session : sessions) {
            // 两端缺一就推不出 conv_key（Math.min/max 会 NPE），跳过这一条与比对器的取舍一致。
            if (session.getUserAId() == null || session.getUserBId() == null) {
                continue;
            }
            try {
                int rows = alignReadStateOfSession(session);
                if (rows > 0) {
                    aligned += rows;
                    touched++;
                }
            } catch (Exception ex) {
                failed++;
                log.warn("[IM 消息镜像] 一次性已读对齐在该会话上失败（跳过，继续下一个）"
                                + "sessionId={}, convKey={}：{}",
                        session.getId(), convKey(session), ex.getMessage());
            }
        }
        log.info("[IM 消息镜像] 一次性已读对齐完成：扫描会话 {} 个（最近 {} 天内更新过的，上限 {} 个）"
                        + "→ 有改动的会话 {} 个、共 {} 行由「未读」改成 status={}（0 行 = 本来就没有假未读）；失败 {} 次。"
                        + "⚠️ 请用 docs/ops/im-platform-migration.md §3.B.3 的比对 SQL 复核两侧未读一致后，"
                        + "把 kean.im.message-mirror-repair-read-state-enabled（环境变量 "
                        + "KEAN_IM_MESSAGE_MIRROR_REPAIR_READ_STATE_ENABLED）改回 false 并重启",
                sessions.size(), repairReadStateDays, compensateLimit, touched, aligned,
                BOX_STATUS_READED, failed);
        return aligned;
    }

    /**
     * 对齐<b>一个会话</b>两端的已读状态（{@code user_a_id} 比 {@code a_read_seq}，
     * {@code user_b_id} 比 {@code b_read_seq}）。
     *
     * @return 本会话被改成已读的行数
     */
    private int alignReadStateOfSession(ChatSession session) {
        String key = convKey(session);
        return alignReadStateOfUser(key, session.getUserAId(), session.getAReadSeq())
                + alignReadStateOfUser(key, session.getUserBId(), session.getBReadSeq());
    }

    /**
     * 对齐<b>某个人</b>在该会话里的已读状态：把「发给 TA 的、{@code seq_no <= TA 的已读位点}」
     * 且仍然是未读（{@code status < 3}）的行置为已读。
     *
     * <p>位点为 NULL 或 ≤ 0 时直接返回 0（不发动这条 SQL）：没有位点就没有「已读到哪」，
     * 此时「保持未读」才是唯一安全的结论 —— 与 {@link #boxStatusOf} 的 NULL 处理完全一致。</p>
     *
     * @return 被改成已读的行数
     */
    private int alignReadStateOfUser(String sessionKey, Long userId, Long readSeq) {
        if (userId == null || readSeq == null || readSeq.longValue() <= 0L) {
            return 0;
        }
        return updateInBox(REPAIR_READ_STATE_SQL, userId, sessionKey, readSeq.longValue());
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private static int clamp(int value, int max) {
        return value > 0 ? Math.min(value, max) : 1;
    }

    /**
     * 解析 {@code im_platform} 的访问通道 —— <b>复用主 DataSource</b>（与
     * {@link ImShadowUserService#resolveJdbcTemplate} 完全同源）。
     *
     * <p>解析失败<b>不抛异常</b>：镜像只是影子通路，绝不能因为它把整个后端启动带崩
     * （与 {@code ImTokenService} 对密钥 / {@code ImShadowUserService} 对数据源的处理同风格）。</p>
     */
    private static JdbcTemplate resolveJdbcTemplate(ObjectProvider<DataSource> provider) {
        try {
            DataSource dataSource = provider.getIfAvailable();
            if (dataSource == null) {
                log.warn("[IM 消息镜像] 容器里没有可用的 DataSource，本次启动不镜像消息（其它功能不受影响）");
                return null;
            }
            return new JdbcTemplate(dataSource);
        } catch (Exception ex) {
            log.warn("[IM 消息镜像] 解析数据源失败（不影响启动）：{}", ex.getMessage());
            return null;
        }
    }

    /**
     * 构造 {@code REQUIRES_NEW} 的事务模板（见类注释「失败策略」第 2 条）。
     *
     * <p>解析失败<b>不抛异常</b>：退回「与调用方共用连接」的写法即可，
     * 这比因为拿不到事务管理器而把整个后端启动带崩要好得多。</p>
     */
    private static TransactionTemplate resolveTransactionTemplate(
            ObjectProvider<PlatformTransactionManager> provider) {
        try {
            PlatformTransactionManager transactionManager = provider.getIfAvailable();
            if (transactionManager == null) {
                log.warn("[IM 消息镜像] 容器里没有 PlatformTransactionManager：box 侧的写将退化为"
                        + "「与 kean 主事务共用连接」（异常仍被吞掉，但少了一层事务隔离）");
                return null;
            }
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            // ⭐ 这一行就是「box 写失败绝不回滚 kean」的机制：挂起外层事务、另开一个连接与事务。
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return template;
        } catch (Exception ex) {
            log.warn("[IM 消息镜像] 解析事务管理器失败（不影响启动，退化为共用连接）：{}", ex.getMessage());
            return null;
        }
    }

    /**
     * 「这条消息不该/不能镜像」的内部信号：既不是投递故障（不该进 failed 与告警），
     * 也不是「开关没开」（不该进 skipped 的 no-op 语义）。
     *
     * <p>继承 {@link RuntimeException} 而不受检：它只在本类内部抛出并被
     * {@link #mirrorQuietly} 立刻捕获，不存在漏检的风险；用受检异常反而会污染
     * {@link #buildArgs} 的签名。</p>
     */
    private static final class MirrorSkipException extends RuntimeException {

        private MirrorSkipException(String message) {
            super(message);
        }
    }

    /**
     * 便于人工核对时打印会话键（与 {@code com.bx.implatform.util.ConvUtil.buildConvKey} 同源）。
     *
     * <p>刻意用 {@link Locale#ROOT} 拼数字，避免某些 locale 下出现千分位分隔符
     * （那会让 {@code conv_key} 变成一个 box 认不出来的字符串）。</p>
     */
    static String describeConvKeyForLog(Long userId1, Long userId2) {
        return String.format(Locale.ROOT, "%d_%d", Math.min(userId1, userId2), Math.max(userId1, userId2));
    }
}
