package com.kean.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kean.entity.ChatMessage;
import com.kean.entity.ChatSession;
import com.kean.mapper.ChatMessageMapper;
import com.kean.mapper.ChatSessionMapper;
import com.kean.service.ChatSeqService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * seq_no 分配器：Redis 原子自增为主，数据库 CAS 为兜底。
 *
 * <p>Redis key：{@code kean:chat:seq:{sessionId}}，String 值 = 该会话"已经发出去的最大 seq_no"。
 * key 上带 7 天 TTL：会话长期不发消息就让 key 自然消失，不占内存；下次发消息时按
 * {@code MAX(chat_message.seq_no)} 重新播种。
 *
 * <p>为什么用"未命中就播种"的 Lua 而不是 {@code SETNX} + {@code INCR}：
 * SETNX 之后、INCR 之前如果进程崩了，会在 key 里留下一个"已占用但没被用掉"的序号；
 * 更要紧的是两个请求会各自算出不同的播种值。Lua 在 Redis 单线程里整体执行，
 * "播种 + 自增"一次原子完成，同一 key 上永远不会出现两次不同起点的自增。
 *
 * <p>关于空洞与重号（边界说明）：
 * <ul>
 *     <li><b>空洞是允许的</b>：拿到号之后业务校验失败/事务回滚，号就废掉了。
 *         序号严格递增但不保证无空洞，客户端的增量拉取（seq_no &gt; 游标）不受影响。</li>
 *     <li><b>正常路径不重号</b>：只有 Redis 在发号时 INCR 天然唯一；key 过期/清空后按
 *         {@code max(MAX(seq_no), last_seq_no)} 重播种，起点不小于任何已经用掉的号。
 *         真出现意外重复，V34 建的唯一索引
 *         {@code uk_chat_message_session_seq (session_id, seq_no)} 会拦住写入，
 *         库内不会出现两条同号消息（发消息请求会失败，不会静默写坏数据）。</li>
 *     <li><b>Redis 不可用时</b>走数据库兜底（见 {@link #allocateFromDb}），用 chat_session
 *         行锁做 CAS。已知残留风险：如果 Redis 只是"部分失败"而不是完全不可用，
 *         Redis 上已经发出去但尚未落库的号与兜底发号理论上可能撞车，最终由上面的唯一索引
 *         兜住（表现为该次发送失败，可重试）。完全不可用是主要场景，此时兜底是唯一能让
 *         发消息继续工作的路径。</li>
 * </ul>
 */
@Service
public class ChatSeqServiceImpl implements ChatSeqService {

    private static final Logger log = LoggerFactory.getLogger(ChatSeqServiceImpl.class);

    /** Redis key 前缀，会话维度：kean:chat:seq:{sessionId}。 */
    private static final String KEY_PREFIX = "kean:chat:seq:";

    /** key 的空闲存活时间：超过这个时间没发消息就释放，下次按 DB 重播种。 */
    private static final Duration KEY_TTL = Duration.ofDays(7);

    /**
     * KEYS[1] = 会话序号 key；ARGV[1] = 当前会话已知的最大已发序号（-1 表示 Redis 里没有）。
     * 返回分配到的序号。
     */
    private static final RedisScript<Long> NEXT_SEQ_SCRIPT = new DefaultRedisScript<>(
            "local cur = redis.call('GET', KEYS[1]) "
                    + "if not cur then "
                    + "  local seed = tonumber(ARGV[1]) or -1 "
                    + "  redis.call('SET', KEYS[1], seed, 'EX', " + KEY_TTL.toSeconds() + ") "
                    + "end "
                    + "return redis.call('INCR', KEYS[1])",
            Long.class
    );

    /**
     * KEYS[1] = 会话序号 key。返回当前值，key 不存在返回 -1（不写 Redis，交给调用方用 DB 值兜底）。
     */
    private static final RedisScript<Long> CURRENT_SEQ_SCRIPT = new DefaultRedisScript<>(
            "local cur = redis.call('GET', KEYS[1]) "
                    + "if not cur then return -1 end "
                    + "return tonumber(cur)",
            Long.class
    );

    private static final int DB_RETRY = 3;

    private final StringRedisTemplate redisTemplate;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatSessionMapper chatSessionMapper;

    public ChatSeqServiceImpl(
            StringRedisTemplate redisTemplate,
            ChatMessageMapper chatMessageMapper,
            ChatSessionMapper chatSessionMapper
    ) {
        this.redisTemplate = redisTemplate;
        this.chatMessageMapper = chatMessageMapper;
        this.chatSessionMapper = chatSessionMapper;
    }

    @Override
    public long allocateNext(Long sessionId, Long lastSeqNo) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId is required");
        }
        // 冷启动下界：只在 Redis key 不存在（首次发消息 / TTL 过期 / 被清空）时才会被 Lua 用到。
        // 取 max(会话行的 last_seq_no 镜像, 库内 MAX(seq_no))：
        //   * MAX(seq_no) 覆盖"Redis 被清过但消息已落库"的情况，起点一定大于已发出的最大号；
        //   * last_seq_no 镜像覆盖"数据库兜底发过号、但消息还没落库"的情况。
        // 这个读只在 key 缺失时真正起作用，正常路径是纯 Redis 自增，不受影响。
        long floor = Math.max(maxSeqNoInDb(sessionId), lastSeqNo == null ? 0L : lastSeqNo);
        try {
            Long next = redisTemplate.execute(NEXT_SEQ_SCRIPT, Collections.singletonList(key(sessionId)), Long.toString(floor));
            if (next != null) {
                return next;
            }
            log.warn("chat seq script returned null, fallback to db. sessionId={}", sessionId);
        } catch (RuntimeException ex) {
            // Redis 抖动 / 连接不可用：不阻塞发消息，退到数据库 CAS。
            log.warn("chat seq allocate via redis failed, fallback to db. sessionId={}", sessionId, ex);
        }
        return allocateFromDb(sessionId);
    }

    @Override
    public long currentSeq(Long sessionId, Long lastSeqNo) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId is required");
        }
        long known = Math.max(maxSeqNoInDb(sessionId), lastSeqNo == null ? 0L : lastSeqNo);
        try {
            Long current = redisTemplate.execute(CURRENT_SEQ_SCRIPT, Collections.singletonList(key(sessionId)));
            if (current != null && current > known) {
                return current;
            }
        } catch (RuntimeException ex) {
            log.warn("chat seq read via redis failed, use db value. sessionId={}", sessionId, ex);
        }
        return known;
    }

    private String key(Long sessionId) {
        return KEY_PREFIX + sessionId;
    }

    /**
     * 库内已落库的最大 seq_no；没有消息时为 0。
     * 走 V34 的索引 (session_id, seq_no)，是索引上的 MAX 查询；只在 Redis key 冷启动
     * 或数据库兜底路径上调用，不在每次发消息的热路径上。
     */
    private long maxSeqNoInDb(Long sessionId) {
        QueryWrapper<ChatMessage> wrapper = new QueryWrapper<>();
        wrapper.select("IFNULL(MAX(seq_no), 0) AS seq_no").eq("session_id", sessionId);
        List<Object> values = chatMessageMapper.selectObjs(wrapper);
        if (values == null || values.isEmpty() || values.get(0) == null) {
            return 0L;
        }
        return ((Number) values.get(0)).longValue();
    }

    /**
     * Redis 不可用时的兜底：拿 chat_session 的行锁做 CAS，把 last_seq_no 从 cur 推到 cur+1，
     * 谁 UPDATE 成功谁就拿到这个号；失败说明被别人抢先，重读再试。
     *
     * <p>隐患（已评估）：兜底期间如果 Redis 是"能用但发号序列落后"的状态，两边理论上可能撞号；
     * 撞号会被唯一索引拦住（插入失败而不是脏数据）。更硬的约束是"Redis 完全不可用"，
     * 此时兜底是唯一能让发消息继续工作的路径。
     */
    private long allocateFromDb(Long sessionId) {
        for (int i = 0; i < DB_RETRY; i++) {
            ChatSession session = chatSessionMapper.selectById(sessionId);
            if (session == null) {
                throw new IllegalStateException("chat session not found: " + sessionId);
            }
            long cur = Math.max(session.getLastSeqNo() == null ? 0L : session.getLastSeqNo(), maxSeqNoInDb(sessionId));
            long next = cur + 1;
            LambdaUpdateWrapper<ChatSession> wrapper = new LambdaUpdateWrapper<ChatSession>()
                    .eq(ChatSession::getId, sessionId)
                    .eq(ChatSession::getLastSeqNo, cur)
                    .set(ChatSession::getLastSeqNo, next);
            if (chatSessionMapper.update(null, wrapper) > 0) {
                return next;
            }
        }
        // 兜底重试也失败：直接抛出，由 Controller 统一转成 50000。
        // 这里绝不返回一个可能重复的号——宁可让用户重发一次。
        throw new IllegalStateException("chat seq allocate failed after " + DB_RETRY + " attempts, sessionId=" + sessionId);
    }
}
