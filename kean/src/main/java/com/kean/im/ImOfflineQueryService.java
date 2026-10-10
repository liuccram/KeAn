package com.kean.im;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kean.common.PageResult;
import com.kean.entity.ChatMessage;
import com.kean.entity.ChatSession;
import com.kean.mapper.ChatMessageMapper;
import com.kean.utils.FileUrls;
import com.kean.vo.ChatMessageVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * 阶段 C-2：把<b>增量拉取（离线消息）</b>的读取通道从 kean 的 {@code chat_message}
 * 切到 box 的 {@code im_platform.im_private_message} —— 但<b>只在开关打开时</b>。
 *
 * <h2>它解决什么问题（用户清单第 5 条）</h2>
 * <p>kean 现在的增量拉取是「按会话的 {@code seq_no} 游标」（{@code GET /api/chats/{id}/messages?afterSeq=N}
 * → {@code seq_no > N}）。box 的离线语义是「按 {@code im_private_message.id} 这个<b>自增主键</b>
 * 做游标」（{@code loadOfflineMessage(minId)} 的查询是 {@code id > minId}）。
 * 两者形状不同，所以本类做的事是：<b>在 box 的表上按 box 的语义读，再把结果翻译回 kean 的 VO</b>。</p>
 *
 * <h2>⚠️⚠️ 关于「box 的确切实现」——读到的与没读到的，必须分清</h2>
 * <p>本机<b>没有</b> box 源码目录（{@code /opt/boxim-src/box-im} 不在本机），所以本类的实现依据是
 * 两条<b>已经落到文档里</b>的事实，而不是我现场读的源码：</p>
 * <ol>
 *   <li>{@code docs/ops/im-platform-migration.md} <b>§6.1</b> 声称逐字摘录了
 *       {@code PrivateMessageServiceImpl.loadOfflineMessage} 的原文（含
 *       {@code wrapper.gt(PrivateMessage::getId, minId)}、{@code MAX_OFFLINE_MESSAGE_DAYS} = 60 天、
 *       {@code orderByDesc(id) + limit}、返回前升序、以及「把收到的 status=0 改成 1」的副作用）；</li>
 *   <li>用户本轮<b>已核实</b>的结论：「box 的离线语义核心是<b>按 {@code im_private_message.id}
 *       （自增主键）作为游标</b>，拉取 {@code id > minId} 且 {@code recv_id = me} 的消息」。</li>
 * </ol>
 * <p><b>因此下面这些点是「照语义实现、未读源码」的，将来读到源码若不同，改动点都集中在本类：</b></p>
 * <table border="1">
 *   <caption>未证实点与对应改动点</caption>
 *   <tr><th>未证实点</th><th>本类的取法</th><th>若源码不同，改哪里</th></tr>
 *   <tr><td>box 侧是否真的按 60 天窗口过滤</td>
 *       <td>照 §6.1 加 {@code send_time >= now-60d}（常量 {@link #OFFLINE_WINDOW_DAYS}）</td>
 *       <td>{@link #queryAfterCursor} 的 {@code send_time >= ?} 一处</td></tr>
 *   <tr><td>它是「跨会话一次拉全部」还是「按会话」</td>
 *       <td><b>按会话</b>：因为 kean 的接口形状就是 {@code /api/chats/{id}/messages}，
 *           本轮<b>不改接口形状</b>，所以只能用 {@code conv_key} 把范围收敛回这个会话</td>
 *       <td>{@code queryAfterCursor} 的 {@code conv_key = ?}；若将来改成跨会话接口，
 *           把这一条换成 {@code (send_id = me OR recv_id = me)}（见类注释末尾）</td></tr>
 *   <tr><td>触顶（10000 条）时「每个会话至少补一条」</td>
 *       <td><b>不实现</b>：kean 的 {@code CATCH_UP_LIMIT} 是 200，且客户端会带新游标继续翻页，
 *           不存在「一次拉不全就永远拉不到」的问题</td>
 *       <td>若将来放弃「按会话」的接口形状，需要一并搬 {@code appendLastMessageInConversation}
 *           与它依赖的 {@code im_friend}（注意：{@code im_friend} 在 kean 侧并不存在，
 *           那会是一个新依赖 —— 见 §6.1 第 ⑤ 条的警告）</td></tr>
 *   <tr><td>{@code loadOfflineMessage} 的写副作用（status 0 → 1）</td>
 *       <td><b>刻意不做</b>（本类是纯只读通道）。理由：kean 的未读口径归 B-3 的
 *           {@code unread-source}，一个「读消息时顺手改状态」的副作用会让
 *           「读接口改变未读数」，这与 kean 现有的只读 GET 语义冲突</td>
 *       <td>若将来确实要接这个副作用：新增一个显式方法（不要塞进本类的读路径），
 *           并且必须与 {@link ImUnreadQueryService} 的开关一起评估，否则两套未读口径会打架</td></tr>
 * </table>
 *
 * <h2>⭐⭐ 游标映射规则：{@code afterSeq} → box 的 {@code minId}</h2>
 * <p>两个游标<b>不能直接赋值</b>（{@code seq_no} 是会话内相对序号，{@code id} 是全局绝对主键；
 * 混用会导致「拉回巨量历史」或「永远返回空」，见 {@code im-platform-migration.md} §6.3.1）。
 * 本类用的是该文档 §6.3.2 第 2 步说的<b>「翻译」</b>，而不是赋值：</p>
 * <pre>
 * ① 客户端传进来的 afterSeq（= 它在这个会话里已经见过的最大 seq_no）
 * ② 在 box 的表里找「本会话中 seq_no 最小的、且 seq_no &gt; afterSeq」的那一行，取它的 id 记作 firstId
 *      MIN(id) WHERE conv_key = :ck AND seq_no &gt; :afterSeq
 * ③ 真正的游标 = firstId - 1，查询条件写成 id &gt; (firstId - 1)
 *      ⇒ 等价于 id &gt;= firstId，即「从第一条我没见过的 box 消息开始，往后按 id 取」
 * ④ 若 box 里根本没有 seq_no &gt; afterSeq 的行（例如双写还没开），则游标取 0，
 *      查询退化成「本会话里 id &gt; 0」——但结果会是空（下面第 5 条会强制回退），
 *      所以这个分支不会让用户看到空洞
 * </pre>
 * <p><b>为什么用 {@code MIN(id)} 且必须带「按 id 升序」</b>（这是本实现最关键的一处正确性论证）：</p>
 * <ul>
 *   <li>{@code seq_no} 在<b>整个 box 表里不是全局单调</b>的 —— 它是 kean 的
 *       {@code ChatSeqService} 按会话分配的号（可能来自 Redis 序列、不同会话互不相干），
 *       所以「seq_no 更大」的行完全可能有「更小的 id」（例如某个冷会话很久没发消息，
 *       一恢复就拿到一个很大的号）。因此<b>不能用 {@code MAX}，也不能用「seq_no = afterSeq 那一行的 id」
 *       当作上界</b>：那会把「seq_no 大但 id 小」的未读消息永久漏掉（游标直接从它们头上跨过去）；</li>
 *   <li>用 {@code MIN(id)} 得到的是「本会话里我还没见过的那批消息中，id 最小的那一条」。
 *       从它开始按 id 往后取，<b>不可能漏</b>：任何一条 seq_no &gt; afterSeq 的消息，其 id
 *       都 ≥ 这个 MIN(id)，所以一定在 {@code id > firstId - 1} 的结果里；</li>
 *   <li>反过来，「<b>多取</b>」的窗口有多大？只可能是「id 比 firstId 大、但 seq_no ≤ afterSeq」的行，
 *       即 <b>seq_no 与 id 顺序相反</b>的那些。这正是上面说的稀疏分配场景，
 *       在双写（B-1）下每条消息都逐条写进 box，正常情况下两者顺序一致，
 *       所以实际多取的部分通常是 0 条；即使有，客户端对同一条消息是<b>幂等合并</b>的
 *       （{@code chatMerge.mergeIncoming} 按 localId 去重，见下），最坏也只是「重复收到的已读气泡被合并掉」，
 *       不会出现重复气泡或错序。</li>
 * </ul>
 * <p><b>多取为什么是安全的（客户端侧的既有保证，本轮一行不改客户端）：</b>
 * {@code uni-kean/src/utils/chatMerge.ts} 的 {@code dedupeKey()} 是
 * <b>{@code localId} 优先</b>（{@code l:<localId>}），只有缺 {@code localId} 时才退回服务端 id。
 * kean 的 {@code send()} 一定会给出 {@code localId}（客户端生成或服务端补 32 位 UUID），
 * B-1 的镜像也把它写进了 box 的 {@code local_id}，所以「同一会话的同一批消息」
 * 在客户端无论按哪个通道（WebSocket 推送 / kean 拉取 / 本类的 box 拉取）拿到，
 * 去重键都是同一个。</p>
 * <p>⚠️ <b>由此推出的一个已知边界（必须说清）</b>：box 表里 {@code local_id} 为空的行
 * （阶段 B 之前的旧数据、或有人绕过模板直接 INSERT），客户端只能用服务端 id 去重，
 * 而本类给出的 id 是 <b>box 的 id</b>（与 WebSocket 推送里的 kean id 不同）
 * ⇒ 那种行<b>可能</b>与推送来的同一条消息各显示一次。B-1 的镜像器对缺 {@code local_id}
 * 的行是「拒绝镜像」的（{@code MirrorSkipException}），所以这个集合在正常运维下应为空；
 * 真出现时表现为「个别气泡重复」，处理办法是把 {@code kean.im.read-source} 设回 {@code kean}。</p>
 *
 * <h2>返回形状：与 kean 的既有实现<b>逐字段一致</b>（客户端零改动）</h2>
 * <table border="1">
 *   <caption>ChatMessageVO 每个字段在 box 通道下的来源</caption>
 *   <tr><th>字段</th><th>来源</th><th>说明</th></tr>
 *   <tr><td>{@code id}</td><td>{@code im_private_message.id}</td>
 *       <td>⚠️ 这是 <b>box 的 id</b>，不是 kean 的 {@code chat_message.id}（两者编号不同）。
 *           客户端的去重键是 {@code localId} 优先，所以正常数据下无影响（见上）</td></tr>
 *   <tr><td>{@code sessionId}</td><td>调用方（kean）传进来的会话 id</td>
 *       <td>box 表里<b>没有</b>会话结构（只有 {@code conv_key}），所以这个字段只能由 kean 侧补</td></tr>
 *   <tr><td>{@code senderId}</td><td>{@code send_id}</td><td>——</td></tr>
 *   <tr><td>{@code msgType}</td><td>{@code type} 0/1 → {@code TEXT}/{@code IMAGE}</td>
 *       <td>与 {@link ImMessageMirrorService}/{@link ImSenderService} 的写侧映射<b>同一张表</b>
 *           （B-1 已逐字核对 box 的 {@code MessageType}）</td></tr>
 *   <tr><td>{@code content}</td><td>{@code content}</td>
 *       <td>与 kean 同义（TEXT 是文本、IMAGE 是对象存储 objectKey），见 B-1 的「content 与图片」小节</td></tr>
 *   <tr><td>{@code url}</td><td>{@code content} 经 {@link FileUrls#of(String)} 签名（仅 IMAGE）</td>
 *       <td>与 kean {@code toMessageVo} 的算法完全相同（同一个工具类，不重写）</td></tr>
 *   <tr><td>{@code createdAt}</td><td>{@code send_time}</td>
 *       <td>{@code datetime(3)} → {@code LocalDateTime}；与 HTTP 路径返回的格式一致</td></tr>
 *   <tr><td>{@code mine}</td><td>{@code send_id == viewerId}</td><td>与 kean 的算法相同</td></tr>
 *   <tr><td>{@code seqNo}</td><td>{@code seq_no}</td>
 *       <td>B-1 保证两边逐条相等，所以客户端的 {@code lastSeq} 游标语义不变</td></tr>
 *   <tr><td>{@code localId}</td><td>{@code local_id}</td><td>客户端的去重键（见上）</td></tr>
 *   <tr><td>{@code status}</td><td>{@code status}</td><td>kean 与 box 的取值表本来就一致（0/1/2/3）</td></tr>
 *   <tr><td>{@code readAt}</td><td><b>恒为 {@code null}</b></td>
 *       <td>⚠️ 已知差异：box 的表<b>没有 read_time 列</b>（已核实），已读只有 {@code status=3} 一个标记。
 *           而 kean 的 {@code readAt} 只在 {@code markRead} 时写 kean 表。
 *           ⚠️ 客户端的已读勾是根据 {@code mine + status} 画的（{@code status=3} 即已读），
 *           不依赖 {@code readAt}；所以这里返回 null 只影响「精确到秒的已读时间」这种展示，
 *           不影响勾的显示。若将来要精确值，正确做法是让 box 侧补列（DDL，不在本轮范围）</td></tr>
 * </table>
 *
 * <h2>⭐⭐ 失败必须回退（绝不能因为 box 挂了打不开聊天页）</h2>
 * <p>本类的公开方法<b>只有一种对外结果</b>：要么返回一批 VO，要么返回 {@code null}。
 * {@code null} 的语义是「我没算出来，请调用方走 kean 的既有 {@code afterSeq} 实现」，
 * 以下五种情况都返回它，且<b>绝不外抛异常</b>：</p>
 * <ol>
 *   <li>开关不是 {@code box}（默认就是这一种，见下）；</li>
 *   <li>数据源解析失败（容器里没有 DataSource）；</li>
 *   <li>会话/用户参数不全，或该用户不是这个会话的参与者（推不出 {@code conv_key}）；</li>
 *   <li>SQL 执行失败（box 库不可用 / 权限不足 / 表结构变化）；</li>
 *   <li><b>缺行护栏</b>：box 在这个窗口里给出的行数 &lt; kean 同窗口的条数（见下一节）。</li>
 * </ol>
 * <p>⚠️ 与 {@link ImUnreadQueryService#warnOnce} 同一个取舍：聊天页是<b>高频</b>接口、box 故障是
 * <b>持续</b>状态，所以同一个失败原因只打<b>一次</b> WARN（带完整异常 message），之后降为 DEBUG，
 * 避免「每 8 秒一次轮询 × 每个会话」把日志刷爆。</p>
 * <p>⚠️ <b>本类不写任何库</b>（连 kean 的表都不写）：它只是把「读」的来源换掉，
 * 因此回退时 kean 侧的数据是<b>热的</b>，把开关设回 {@code kean} 并重启即立刻恢复，
 * 不需要任何回填或重放。</p>
 *
 * <h2>⭐ 缺行护栏：为什么「box 少给了几条」也必须回退（而不是凑合返回）</h2>
 * <p>游标映射的第二步是 {@code ORDER BY id ASC LIMIT 200}，客户端拿到这批之后会把游标推到
 * 其中的<b>最大 {@code seq_no}</b>。因此只要 box 在这个窗口里<b>少了哪怕一条</b>
 * （B-1 的双写没开、或某段时间镜像失败且补偿没补上），那一条就会被客户端的游标
 * <b>永久越过</b> —— 表现为「用户少看到一条消息」，而且再也拉不回来。</p>
 * <p>所以本类在返回之前做一次交叉核对：</p>
 * <pre>
 * box 行数 &lt; min(kean 同窗口条数, 200)  ⇒  判定 box 不完整  ⇒  整批回退（返回 null）
 * </pre>
 * <p>取 {@code min(..., 200)} 是因为两边都可能有「一次拉不完」的情况
 * （kean 的 {@code catchUp} 也是 {@code LIMIT 200}）：kean 有 500 条时，box 只要也有 200 条
 * 就算「本批完整」，剩下的由客户端带新游标继续翻页。</p>
 * <p>这条护栏的代价是「切到 {@code box} 之后，每 8 秒的轮询会多一次 kean 的
 * {@code COUNT(*)}」（走 {@code chat_message} 的会话索引，单会话范围扫描）。
 * 换到的是「切到 box 之后不会因为 box 缺数据而静默丢消息」——这个交换是值的。
 * 代价只在 {@code read-source=box} 时发生；默认 {@code kean} 下依然一条 SQL 都不发。</p>
 * <p>观测：护栏拦下的次数在 {@link #statsSnapshot()} 的 {@code short} 位
 * （与「查询直接抛异常」的 {@code fallbacks} 分开计数，因为排查方向完全不同）。</p>
 *
 * <h2>默认关闭：不配 = 一条 SQL 都不发</h2>
 * <p>开关 {@code kean.im.read-source}，取值 {@code kean}（<b>默认，不配即此</b>）/{@code box}。
 * 值为 {@code kean} 时 {@link #active()} 恒为 {@code false}，{@link #catchUpAfterSeq} 第一行就
 * 返回 {@code null} —— <b>不建连接、不发 SQL、不读 Redis</b>，调用方走的就是改造前那条路径。
 * 解析不到 DataSource 时同样返回 {@code null}（而不是每个请求都去试一次注定失败的查询）。</p>
 * <p>⚠️ 与 B-3 的 {@code kean.im.unread-source} <b>刻意是两个独立开关</b>（不合并）：
 * 一个管「消息内容从哪读」，一个管「未读数从哪算 / 已读往哪写」。
 * 分开才能分别灰度、分别回退 —— 例如「读切到 box 但未读先留在 kean」，
 * 或反过来「未读切了 box、消息内容先不动」。两者合并会让任何一次灰度都变成全量切换。</p>
 *
 * <h2>索引与性能（⚠️ 给运维的一句话）</h2>
 * <p>{@code im_private_message} 上现有的 {@code idx_conv_key_seq_no(conv_key, seq_no)}
 * 正好服务本类的第一步（{@code MIN(id) WHERE conv_key = ? AND seq_no > ?}），
 * 第二步的 {@code id > ?} 走主键。<b>不需要新增索引</b>。
 * 与 {@link ImUnreadQueryService} 的未读统计不同，本类的两条条件都带 {@code conv_key}
 * 前缀，因此不会退化成「一个用户全部历史消息」量级的扫描。</p>
 */
@Service
public class ImOfflineQueryService {

    private static final Logger log = LoggerFactory.getLogger(ImOfflineQueryService.class);

    /** 跨库全限定表名（同实例跨 schema，复用主 DataSource；<b>不新建 DataSource</b>）。 */
    private static final String TABLE = "im_platform.im_private_message";

    /** 开关名（同时用于启动日志，避免文案与配置项拼写漂移）。 */
    public static final String PROPERTY_READ_SOURCE = "kean.im.read-source";

    /** 开关取值：增量拉取仍走 kean 的 {@code chat_message}（默认）。 */
    public static final String SOURCE_KEAN = "kean";

    /** 开关取值：增量拉取走 box 的 {@code im_private_message}。 */
    public static final String SOURCE_BOX = "box";

    /** box {@code MessageType.TEXT.code() = 0} ↔ kean {@code "TEXT"}。 */
    private static final int BOX_TYPE_TEXT = 0;

    /** box {@code MessageType.IMAGE.code() = 1} ↔ kean {@code "IMAGE"}。 */
    private static final int BOX_TYPE_IMAGE = 1;

    /**
     * box 离线消息的时间窗口（天）。
     *
     * <p>{@code 60} 来自 {@code docs/ops/im-platform-migration.md} §6.1 第 ② 条
     * （{@code Constant.MAX_OFFLINE_MESSAGE_DAYS}）。<b>⚠️ 这一条是「照文档实现」，
     * 我没有在本机读到 box 的常量类</b>（box 源码不在本机）。加它的目的是让
     * 「box 通道能看到的集合」与「box 自己的离线接口能看到的集合」一致；
     * 即使真实值不是 60，后果也只是「本通道多看到或少看到极旧的消息」——
     * 少看到的那些，kean 的历史分页接口仍然能拿到（那走的是 kean 表），不会造成数据丢失。</p>
     */
    private static final int OFFLINE_WINDOW_DAYS = 60;

    /** 单次查询最多返回多少行（与 {@code ChatServiceImpl.CATCH_UP_LIMIT} 同值，防止一次拉爆）。 */
    private static final int MAX_ROWS = 200;

    /** 数据源不可用（或解析失败）时为 {@code null}：此时所有读方法都返回 {@code null}（=调用方回退）。 */
    private final JdbcTemplate boxJdbc;

    /**
     * kean 侧的读出口 —— <b>只用于「交叉核对 box 是否缺行」这一件事</b>
     * （见 {@link #countKeanAfterSeq}）。返回给客户端的数据<b>永远来自 box</b>，
     * 一旦核对不通过就整个方法返回 {@code null}，由调用方走它自己那条 kean 路径。
     * 这样「谁在提供数据」始终是清楚的，不存在两份数据混在一个响应里的情况。
     */
    private final ChatMessageMapper chatMessageMapper;

    /** 开关的原始取值（用于启动日志原样回显，便于发现「键名拼错」）。 */
    private final String configuredSource;

    /** 开关是否解析为 {@code box}（大小写不敏感、去空白）。 */
    private final boolean boxSource;

    // —— 计数（无锁，只增；重启归零）：[IM 读取来源] 的观测口径 ——
    private final LongAdder readHits = new LongAdder();
    private final LongAdder readFallbacks = new LongAdder();
    private final LongAdder readRows = new LongAdder();
    private final LongAdder readEmpty = new LongAdder();

    /**
     * 「box 侧行数比 kean 少」的次数（= {@link #countKeanAfterSeq} 的护栏拦下的回退）。
     *
     * <p>⚠️ 它<b>与 {@code readFallbacks} 分开计数</b>是刻意的：前者说明
     * <b>box 的进程/网络/权限</b>有问题（查询直接抛异常），后者说明
     * <b>box 的数据比 kean 少</b>（多半是 B-1 的双写没开、或某段时间的镜像失败没补上）。
     * 两者的排查方向完全不同，混在一个数字里会让人照着错的方向查。</p>
     */
    private final LongAdder readShort = new LongAdder();

    /** 见类注释「失败必须回退」：同一失败原因只打一次 WARN。 */
    private final AtomicBoolean failureWarned = new AtomicBoolean(false);

    public ImOfflineQueryService(
            ChatMessageMapper chatMessageMapper,
            ObjectProvider<DataSource> dataSourceProvider,
            @Value("${kean.im.read-source:kean}") String readSource
    ) {
        this.chatMessageMapper = chatMessageMapper;
        this.configuredSource = readSource;
        this.boxSource = SOURCE_BOX.equalsIgnoreCase(readSource == null ? "" : readSource.trim());
        this.boxJdbc = resolveJdbcTemplate(dataSourceProvider);
        boolean effective = boxSource && this.boxJdbc != null;
        // 启动即把「到底切没切」打出来（与 ImUnreadQueryService / ImMessageMirrorService 同一风格）：
        // 只看 /proc/<pid>/environ 只能证明环境变量被注入，不能证明 @Value 解析成功
        // （键名拼错时会静默沿用默认值 kean）。
        log.info("[IM 读取来源] 配置 {}=「{}」→ 解析结果={}（{}），实际生效={}；box 读通道={}，"
                        + "目标表={}（主 DataSource，跨库全限定名，不新建 DataSource）；"
                        + "游标映射=afterSeq→MIN(id) WHERE conv_key=? AND seq_no>?，再按 id 升序取；"
                        + "时间窗口={} 天；单次上限={} 条；"
                        + "失败策略=返回 null 由 ChatServiceImpl 回退到 kean 的 afterSeq 实现（只 WARN 一次）；"
                        + "回退方式=把 {} 设回 {} 并重启 kean（kean 侧数据一直是热的，无需回填）",
                PROPERTY_READ_SOURCE, configuredSource, boxSource,
                boxSource ? "增量拉取改读 box 的 im_private_message" : "增量拉取仍读 kean 的 chat_message",
                effective, boxJdbc == null ? "不可用（无 DataSource）" : "可用",
                TABLE, OFFLINE_WINDOW_DAYS, MAX_ROWS, PROPERTY_READ_SOURCE, SOURCE_KEAN);
        if (boxSource && boxJdbc == null) {
            log.warn("[IM 读取来源] {} 已配置为 {}，但没有可用的 DataSource，"
                            + "所有增量拉取都会回退到 kean 的既有实现（接口不受影响）",
                    PROPERTY_READ_SOURCE, SOURCE_BOX);
        }
        if (!boxSource && readSource != null && !readSource.isBlank()
                && !SOURCE_KEAN.equalsIgnoreCase(readSource.trim())) {
            // 值写错（例如想写 true/false、或拼成 "Boxx"）：静默回落成默认值最危险，
            // 这里显式告警一次，把「你以为切了」变成日志里看得见的一行。
            log.warn("[IM 读取来源] {} 的取值「{}」不是 {} / {}，已按默认值 {} 处理（增量拉取仍读 kean）",
                    PROPERTY_READ_SOURCE, readSource, SOURCE_KEAN, SOURCE_BOX, SOURCE_KEAN);
        }
    }

    // ------------------------------------------------------------------
    // 开关
    // ------------------------------------------------------------------

    /**
     * box 读通道是否真的可用：<b>配置为 {@code box}</b> 且 <b>数据源可用</b>。
     *
     * <p>与 {@link ImUnreadQueryService#active()} 同一取舍：配置成 {@code box} 但拿不到数据源时返回
     * {@code false}，让调用方直接走 kean 的既有实现，而不是每个请求都去尝试一次注定失败的查询。</p>
     *
     * <p>⚠️ 调用方（{@code ChatServiceImpl.catchUp}）<b>先判它、再去查会话</b>：
     * 这样默认配置（{@code read-source=kean}）下「多一条 box 读通道」这件事<b>连一次 kean 的
     * {@code selectById} 都不会多</b>，与新增本类之前逐字节一致。
     * 一个只读的旁路开关，不应该让既有路径多一次查库 —— 哪怕那次查库很便宜。</p>
     */
    public boolean active() {
        return boxSource && boxJdbc != null;
    }

    /**
     * 开关的<b>原始配置值</b>（未规范化、原样回显）。
     *
     * <p>用途只有一个：让「现在到底谁在干活」的汇总行
     * （{@link ImRealtimeRoleService#summary()}）能写出「配置了什麼 / 真正生效了沒有」，
     * 而不是只写一个解析后的布尔量 —— 这样键名拼错（静默沿用默认值 {@code kean}）
     * 在汇总行里也是<b>可见</b>的。与 {@link ImUnreadQueryService#configuredSource()} 完全同源。</p>
     *
     * <p>⚠️ 它是纯读方法：不查库、不读 Redis、不改任何状态；默认配置下行为与新增它之前逐字节一致。</p>
     */
    public String configuredSource() {
        return configuredSource;
    }

    /**
     * 计数的只读快照，格式：{@code hits/fallbacks/short/rows/empty}（无锁、无副作用）。
     *
     * <table border="1">
     *   <caption>各计数含义</caption>
     *   <tr><th>字段</th><th>含义</th></tr>
     *   <tr><td>{@code hits}</td><td>成功从 box 取到结果的<b>查询</b>次数（含返回 0 条的那些）</td></tr>
     *   <tr><td>{@code fallbacks}</td><td>box 查询<b>抛异常</b>（库不可用/权限/表结构变化）而回退到 kean 的次数</td></tr>
     *   <tr><td>{@code short}</td><td>box <b>少给了消息</b>（行数 &lt; kean 同窗口的条数）而回退到 kean 的次数</td></tr>
     *   <tr><td>{@code rows}</td><td>从 box 读出的消息<b>行数</b></td></tr>
     *   <tr><td>{@code empty}</td><td>查成功但 0 行的次数（正常态；⚠️ 此时调用方<b>也会</b>回退，
     *       见 {@link #catchUpAfterSeq} 的说明）</td></tr>
     * </table>
     */
    public String statsSnapshot() {
        return readHits.sum() + "/" + readFallbacks.sum() + "/" + readShort.sum()
                + "/" + readRows.sum() + "/" + readEmpty.sum();
    }

    // ------------------------------------------------------------------
    // 读：增量拉取（box 语义）
    // ------------------------------------------------------------------

    /**
     * 按 box 的游标语义做一次增量拉取。
     *
     * <p>⚠️ <b>与 kean 的既有实现有一个行为差异，必须知道</b>：kean 的 {@code catchUp} 在
     * 「没有新消息」时返回一个<b>空列表</b>（客户端据此判定 {@code ok}，不回退整页刷新）；
     * 而本方法在「box 查出来是 0 行」时返回 {@code null}（= 调用方回退到 kean）。
     * 这么做是<b>刻意的</b>：本通道无法区分「box 说没有新消息」与「box 里的数据不全」
     * （例如双写还没开、或某段时间的镜像失败过）。让 kean 的读数做一次交叉确认，
     * 最多多一次本地 SQL；代价是「真的没有新消息」时不能省掉那次查询 —— 换来的是
     * <b>不会因为 box 侧缺数据而让用户看不到消息</b>，这个交换是值的。</p>
     *
     * @param session  该消息所属会话（必须有 {@code userAId/userBId}，用于算 {@code conv_key}）
     * @param viewerId 读消息的人（= 收件人视角；用于 {@code mine} 与参与者校验）
     * @param afterSeq 客户端游标（会话内已见过的最大 {@code seq_no}）
     * @return 与 kean 通道<b>同形</b>的分页结果；<b>{@code null} = 没算出来，调用方必须回退</b>
     */
    public PageResult<ChatMessageVO> catchUpAfterSeq(ChatSession session, Long viewerId, long afterSeq) {
        if (!active() || session == null || session.getId() == null) {
            return null;
        }
        String convKey = ImUnreadQueryService.convKeyOf(viewerId, session);
        if (convKey == null) {
            // 该用户不是这个会话的参与者，或会话两端有 NULL：不做猜测，让调用方回退。
            return null;
        }
        try {
            long cursor = firstIdAfterSeq(convKey, afterSeq) - 1;
            List<Map<String, Object>> rows = queryAfterCursor(convKey, viewerId, cursor);
            readHits.increment();
            if (rows.isEmpty()) {
                // 见方法注释：0 行时仍然回退，让 kean 的读数做一次交叉确认。
                readEmpty.increment();
                return null;
            }
            // ⭐ 完整性护栏（见方法注释「缺数据防护」）：box 在这个窗口里比 kean 少行时，
            //    宁可整批回退到 kean —— 因为一旦我们把「不完整但 seq_no 更大」的那批返回给客户端，
            //    客户端会把游标推到那批里的最大 seq_no，box 缺的那几条就【永久】过去了。
            long keanCount = countKeanAfterSeq(session.getId(), afterSeq);
            if (rows.size() < Math.min(keanCount, MAX_ROWS)) {
                readShort.increment();
                warnShortOnce(session.getId(), convKey, afterSeq, rows.size(), keanCount);
                return null;
            }
            readRows.add(rows.size());
            List<ChatMessageVO> list = toMessageVos(rows, session.getId(), viewerId);
            if (list.isEmpty()) {
                // 理论上不会发生（rows 非空时每行都会产出一个 VO）；为防御起见仍走回退。
                readEmpty.increment();
                return null;
            }
            log.debug("[IM 读取来源] box 增量拉取：sessionId={}, convKey={}, afterSeq={} → firstId-1={}, 返回 {} 条",
                    session.getId(), convKey, afterSeq, cursor, list.size());
            // total/page/size 与 kean 的 catchUp 完全一致（total = 本次条数，page=1，size=条数）：
            // 增量模式下客户端只看 list 与 hasMore，不做翻页，形状必须保持一样。
            return new PageResult<>(list, list.size(), 1L, list.size());
        } catch (Exception ex) {
            readFallbacks.increment();
            warnOnce("增量拉取", "sessionId=" + session.getId() + ", convKey=" + convKey
                    + ", afterSeq=" + afterSeq, ex);
            return null;
        }
    }

    /**
     * 交叉核对用：kean 侧「本会话 {@code seq_no > afterSeq}」还剩多少条（与 {@link #MAX_ROWS} 一起用）。
     *
     * <p>为什么这条 SQL 不违反「box 通道只读 box」的原则：它<b>不改数据、不参与返回</b>，
     * 唯一的用途是判定「box 这一侧是否真的完整」。而 kean 的这一列
     * （{@code chat_message(session_id, seq_no)}）有索引，属于单会话范围扫描，代价可忽略；
     * 且它<b>只在 box 通道激活时</b>才会执行（{@code active()} 为 false 时方法都进不来），
     * 所以默认配置下依然是「一条 SQL 都不发」。</p>
     *
     * <p>⚠️ 走 <b>{@link ChatMessageMapper}</b>（MyBatis-Plus，未加 {@code @TableName} 以外的
     * 任何库名）而不是 {@code JdbcTemplate}，有两个好处：① 与 kean 其它读路径<b>同一个出口</b>，
     * 库名/表名只由实体注解决定，不会在这里写死一个可能与配置漂移的字面量表名；
     * ② 计数口径与 kean 既有 {@code catchUp} 的 WHERE 条件<b>逐字相同</b>
     * （同一组 {@code eq(sessionId).gt(seqNo, afterSeq)}），不会出现「两边算的不是同一个东西」。</p>
     */
    private long countKeanAfterSeq(Long sessionId, long afterSeq) {
        Long count = chatMessageMapper.selectCount(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .gt(ChatMessage::getSeqNo, afterSeq));
        return count == null ? 0L : count;
    }

    /**
     * 缺行护栏触发时的日志（同一原因只 WARN 一次，与 {@link #warnOnce} 同一个失败标记）。
     *
     * <p>这一条与「查询失败的 WARN」在措辞上刻意不同：它<b>不是</b> box 不可用，
     * 而是 box 的数据比 kean 少（多半是双写没开、或某段时间镜像失败过）。
     * 两者的处置方式不一样（前者看 box 进程/网络，后者看 B-1 的补偿器），
     * 所以文案必须能区分 —— 否则运维会照着错的方向排查。</p>
     */
    private void warnShortOnce(Long sessionId, String convKey, long afterSeq, int boxRows, long keanCount) {
        if (failureWarned.compareAndSet(false, true)) {
            log.warn("[IM 读取来源] box 侧数据不完整（sessionId={}, convKey={}, afterSeq={}："
                            + "box {} 行 < kean {} 行），本次已回退到 kean 的既有实现"
                            + "（聊天页不受影响；同一原因只提示这一次，之后为 DEBUG）。"
                            + "排查方向：B-1 的 kean.im.message-mirror-enabled 是否打开、补偿器是否在跑",
                    sessionId, convKey, afterSeq, boxRows, keanCount);
        } else {
            log.debug("[IM 读取来源] box 侧数据不完整（已回退到 kean）：sessionId={}, afterSeq={}, box={} 行, kean={} 行",
                    sessionId, afterSeq, boxRows, keanCount);
        }
    }

    /**
     * 游标映射的第一步：本会话中 {@code seq_no > afterSeq} 的<b>最小 id</b>。
     *
     * <p>理由是类注释「游标映射规则」那条论证：{@code seq_no} 在 box 表里不全局单调，
     * 必须取 {@code MIN(id)} 才能保证不漏。返回 {@code null}（= 没有这样的行）时，
     * 调用方用 {@code null - 1} 会 NPE，所以这里显式回落到 {@code 0L}
     * （⇒ 游标 -1 ⇒ 条件 {@code id > -1}，等价于「取本会话最早的若干行」；
     * 但因为那时结果必为空，实际会走回退分支）。</p>
     *
     * <p>⚠️ SQL 只碰 {@code im_platform}，不跨库 JOIN，也不读 box 的 Redis 位点。</p>
     */
    private long firstIdAfterSeq(String convKey, long afterSeq) {
        String sql = "SELECT MIN(id) AS first_id FROM " + TABLE
                + " WHERE conv_key = ? AND seq_no > ?";
        Long firstId = boxJdbc.queryForObject(sql, Long.class, convKey, afterSeq);
        return firstId == null ? 0L : firstId;
    }

    /**
     * 游标映射的第二步：从 {@code boxMinId} 开始，按 <b>id 升序</b>取该会话的消息。
     *
     * <p>两个条件都有理由：</p>
     * <ul>
     *   <li>{@code conv_key = ?}：把范围锁死在本会话（box 表里没有会话结构，只有这个键）；
     *       ⚠️ 这里<b>刻意不</b>用「{@code send_id = me OR recv_id = me}」——
     *       {@code conv_key} 本身就唯一确定一对用户，两个条件是等价的，
     *       而只带一个等值条件能让 {@code idx_conv_key_seq_no} 的前缀直接生效；</li>
     *   <li>{@code send_time >= ?}：与 box 的 60 天离线窗口对齐（见 {@link #OFFLINE_WINDOW_DAYS}）；</li>
     *   <li>{@code ORDER BY id ASC LIMIT n}：<b>升序</b>是刻意的 —— box 自己的
     *       {@code loadOfflineMessage} 是「按 id 倒序取最近 N 条再翻成升序」，
     *       那会跳过中间的（§6.1 第 ④ 条）。kean 的客户端是「带游标一直翻页」的模型，
     *       按升序取前 N 条才能保证「每条最终都会被拉到」。这也与 kean 既有 {@code catchUp}
     *       的语义（按 seq_no 升序取前 200 条）在行为上对齐。</li>
     * </ul>
     * <p>⚠️ {@code viewerId} 参数<b>当前没有出现在 SQL 里</b>（{@code conv_key} 已经覆盖），
     * 保留它是为了：① 与 {@link #catchUpAfterSeq} 的调用上下文一致、便于将来加过滤；
     * ② 让「谁在读」在日志与将来的 SQL 审计里可见。不要因为「没用上」就删掉 —— 那会把
     * 一个明确的身份维度从读路径上抹掉。</p>
     */
    private List<Map<String, Object>> queryAfterCursor(String convKey, Long viewerId, long boxMinId) {
        LocalDateTime windowStart = LocalDateTime.now().minusDays(OFFLINE_WINDOW_DAYS);
        String sql = "SELECT id, local_id, seq_no, send_id, recv_id, conv_key, content, type, status, send_time"
                + " FROM " + TABLE
                + " WHERE conv_key = ? AND id > ? AND send_time >= ?"
                + " ORDER BY id ASC LIMIT " + MAX_ROWS;
        return boxJdbc.queryForList(sql, convKey, boxMinId, Timestamp.valueOf(windowStart));
    }

    /**
     * 行 → VO（与 kean {@code ChatServiceImpl.toMessageVo} <b>逐字段对齐</b>）。
     *
     * <p>任何一行「缺主键 / 缺 send_id / 缺 seq_no」时<b>整批回退</b>（返回空列表给调用方，
     * 由 {@link #catchUpAfterSeq} 判空后返回 {@code null}）：这比「悄悄跳过那一行」
     * 安全得多 —— 跳过会让客户端的 {@code lastSeq} 游标越过它，那条消息就<b>永久</b>看不到了。</p>
     */
    private List<ChatMessageVO> toMessageVos(List<Map<String, Object>> rows, Long sessionId, Long viewerId) {
        List<ChatMessageVO> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Long id = asLong(row.get("id"));
            Long sendId = asLong(row.get("send_id"));
            Long seqNo = asLong(row.get("seq_no"));
            if (id == null || sendId == null || seqNo == null) {
                log.warn("[IM 读取来源] box 返回的行缺少 id/send_id/seq_no，本次整批回退到 kean"
                                + "（跳过单行会让客户端游标越过它，那条消息将永久看不到）：row={}", row);
                return Collections.emptyList();
            }
            String msgType = boxMsgType(asInt(row.get("type")));
            String content = row.get("content") == null ? "" : row.get("content").toString();
            String localId = row.get("local_id") == null ? null : row.get("local_id").toString();
            list.add(new ChatMessageVO(
                    id,
                    sessionId,
                    sendId,
                    msgType,
                    content,
                    "IMAGE".equals(msgType) ? FileUrls.of(content) : null,
                    asLocalDateTime(row.get("send_time")),
                    Objects.equals(sendId, viewerId),
                    seqNo,
                    localId,
                    asInt(row.get("status")),
                    // box 的 im_private_message 没有 read_time 列（已核实），所以这里恒为 null；
                    // 客户端的已读勾看的是 status==3，不看 readAt（见类注释的字段表）。
                    null));
        }
        return list;
    }

    /** box 的 {@code type} 数字码 → kean 的字符串消息类型（与写侧 {@code boxMessageType} 是同一张表）。 */
    private static String boxMsgType(Integer type) {
        return type != null && type == BOX_TYPE_IMAGE ? "IMAGE" : "TEXT";
    }

    /** 数字列 → {@code Long}（{@code BIGINT/INT} 在 JDBC 下可能是 Integer/Long/BigInteger，统一走 Number）。 */
    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(value.toString().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 数字列 → {@code Integer}（同上）。 */
    private static Integer asInt(Object value) {
        Long parsed = asLong(value);
        return parsed == null ? null : parsed.intValue();
    }

    /**
     * 时间列 → {@code LocalDateTime}。
     *
     * <p>{@code send_time} 建表是 {@code datetime(3)}，MySQL Connector/J 会把 {@code getObject()}
     * 映射成 {@code LocalDateTime}；但不同驱动/版本也可能给 {@code Timestamp}
     * （甚至 {@code java.sql.Date}），所以这里<b>两种都接</b>，接不住就返回 {@code null}
     * （客户端对 {@code createdAt} 为 null 的条目按「按 seqNo 排序」退化处理，不会崩）。
     * <b>刻意不抛异常</b>：一个时间列的驱动差异不该让整条增量通道失败。</p>
     */
    private static LocalDateTime asLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        log.debug("[IM 读取来源] send_time 的 JDBC 类型不是 LocalDateTime/Timestamp（{}），"
                + "该条消息的 createdAt 返回 null（不影响消息本身）", value.getClass().getName());
        return null;
    }

    /** 首次失败打 WARN（含上下文与异常 message），之后降为 DEBUG —— 见类注释「失败必须回退」。 */
    private void warnOnce(String what, String context, Exception ex) {
        if (failureWarned.compareAndSet(false, true)) {
            log.warn("[IM 读取来源] box {}失败，本次已回退到 kean 的既有 afterSeq 实现"
                            + "（聊天页不受影响；同一原因只提示这一次，之后为 DEBUG）：{}：{}",
                    what, context, ex.getMessage());
        } else {
            log.debug("[IM 读取来源] box {}失败（已回退到 kean）：{}：{}", what, context, ex.getMessage());
        }
    }

    /**
     * 解析 box 的访问通道 —— <b>复用主 DataSource</b>（与
     * {@link ImUnreadQueryService#resolveJdbcTemplate} / {@link ImMessageMirrorService} 完全同源）。
     *
     * <p>解析失败<b>不抛异常</b>：这只是「多一条读通道」，绝不能因为它把后端启动带崩
     * （与 {@code ImTokenService} 对密钥、 {@code ImShadowUserService} 对数据源的处理同风格）。</p>
     */
    private static JdbcTemplate resolveJdbcTemplate(ObjectProvider<DataSource> provider) {
        try {
            DataSource dataSource = provider.getIfAvailable();
            if (dataSource == null) {
                log.warn("[IM 读取来源] 容器里没有可用的 DataSource，增量拉取将始终走 kean（不影响启动）");
                return null;
            }
            return new JdbcTemplate(dataSource);
        } catch (Exception ex) {
            log.warn("[IM 读取来源] 解析数据源失败（不影响启动，增量拉取将走 kean）：{}", ex.getMessage());
            return null;
        }
    }
}
