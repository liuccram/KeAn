package com.kean.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.kean.entity.ChatMessage;
import com.kean.entity.ChatSession;
import com.kean.entity.SubstituteApplication;
import com.kean.entity.SubstituteTask;
import com.kean.mapper.ChatMessageMapper;
import com.kean.mapper.ChatSessionMapper;
import com.kean.mapper.SubstituteApplicationMapper;
import com.kean.mapper.SubstituteTaskMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * 文件读取的归属校验。
 *
 * <p>敏感目录（report / appeal / chat / fulfill）除 URL 签名之外，还必须证明请求者与该文件
 * 存在实际关系，不能只判断"是否已登录"。</p>
 *
 * <p>对象键格式为 {@code folder/userId/uuid.ext}，其中 userId 是<b>上传者</b>。因此只有
 * report / appeal 可以直接用该段判断归属；chat 需要会话参与关系，fulfill 需要任务双方关系。</p>
 */
@Component
public class FileAccessGuard {

    /** 头像与封面按设计公开，无需凭证。 */
    private static final Set<String> PUBLIC_FOLDERS = Set.of("avatar", "cover");

    /**
     * chat / fulfill 的归属判定需要反查数据库，而同一张图在一次页面渲染中可能被请求多次，
     * 因此加一层短 TTL 缓存。
     *
     * <p>允许与拒绝都缓存 —— 拒绝同样值得省下一次查询，否则"猜 key"的请求会每次都打库。
     * TTL 取 45 秒：足够吸收一次渲染内的重复请求，又短到"新被接受的申请人""新建的会话"
     * 能在可接受的时间内生效。</p>
     */
    private static final Duration RELATION_CACHE_TTL = Duration.ofSeconds(45);
    private static final long RELATION_CACHE_MAX_SIZE = 10_000;

    private final ChatMessageMapper chatMessageMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final SubstituteTaskMapper substituteTaskMapper;
    private final SubstituteApplicationMapper substituteApplicationMapper;
    private final Cache<String, Boolean> relationCache;

    /**
     * Spring 注入用的构造器。
     *
     * <p>必须显式标注 {@code @Autowired}：本类还有下面的测试用构造器，而 Spring 只在
     * "有且仅有一个构造器"时才自动选择它；构造器多于一个且都没标注时，它会退回无参构造器，
     * 于是报 {@code No default constructor found} 导致整个上下文启动失败。
     * 回归保护见 {@code FileAccessGuardWiringTest}。</p>
     */
    @Autowired
    public FileAccessGuard(
            ChatMessageMapper chatMessageMapper,
            ChatSessionMapper chatSessionMapper,
            SubstituteTaskMapper substituteTaskMapper,
            SubstituteApplicationMapper substituteApplicationMapper
    ) {
        this(chatMessageMapper, chatSessionMapper, substituteTaskMapper, substituteApplicationMapper,
                Ticker.systemTicker());
    }

    /** 供测试注入可控时钟，用于验证 TTL 过期行为。 */
    FileAccessGuard(
            ChatMessageMapper chatMessageMapper,
            ChatSessionMapper chatSessionMapper,
            SubstituteTaskMapper substituteTaskMapper,
            SubstituteApplicationMapper substituteApplicationMapper,
            Ticker ticker
    ) {
        this.chatMessageMapper = chatMessageMapper;
        this.chatSessionMapper = chatSessionMapper;
        this.substituteTaskMapper = substituteTaskMapper;
        this.substituteApplicationMapper = substituteApplicationMapper;
        this.relationCache = Caffeine.newBuilder()
                .maximumSize(RELATION_CACHE_MAX_SIZE)
                .expireAfterWrite(RELATION_CACHE_TTL)
                .ticker(ticker)
                .build();
    }

    /**
     * @param objectKey 已归一化并校验过的对象键，形如 {@code folder/userId/uuid.ext}
     * @param userId    当前登录用户 id；匿名请求传 {@code null}
     * @param admin     当前登录用户是否为管理员
     * @return 是否允许读取该对象
     */
    public boolean canRead(String objectKey, Long userId, boolean admin) {
        if (!StringUtils.hasText(objectKey)) {
            return false;
        }
        String folder = folderOf(objectKey);
        if (folder == null) {
            return false;
        }
        if (PUBLIC_FOLDERS.contains(folder)) {
            return true;
        }
        // 未知目录一律拒绝：将来新增目录必须显式登记，不会因为漏配而默认放行。
        // 管理员放行也只覆盖已登记的敏感目录。
        if (!FileUrlSigner.SENSITIVE_FOLDERS.contains(folder)) {
            return false;
        }
        if (userId == null) {
            return false;
        }
        if (admin) {
            return true;
        }
        return switch (folder) {
            case "report", "appeal" -> Objects.equals(userId, ownerIdOf(objectKey));
            case "chat" -> cachedRelation(objectKey, userId, () -> isChatParticipant(objectKey, userId));
            case "fulfill" -> cachedRelation(objectKey, userId, () -> isTaskParty(objectKey, userId));
            default -> false;
        };
    }

    /**
     * 只有需要查库的判定走缓存；report / appeal 是纯字符串比较，缓存没有收益。
     * 缓存键用「用户 + 对象键」—— 两者缺一都无法决定结果。
     */
    private boolean cachedRelation(String objectKey, Long userId, BooleanSupplier compute) {
        Boolean cached = relationCache.get(userId + "|" + objectKey, key -> compute.getAsBoolean());
        return Boolean.TRUE.equals(cached);
    }

    /** 聊天图片：请求者必须是包含该图片的会话参与者（发送方或接收方）。 */
    private boolean isChatParticipant(String objectKey, Long userId) {
        List<ChatMessage> messages = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getContent, objectKey)
                .last("limit 1"));
        if (messages.isEmpty()) {
            return false;
        }
        ChatSession session = chatSessionMapper.selectById(messages.get(0).getSessionId());
        if (session == null) {
            return false;
        }
        return Objects.equals(userId, session.getUserAId()) || Objects.equals(userId, session.getUserBId());
    }

    /** 履约照片：请求者必须是该任务的发布者或被接受的申请人。 */
    private boolean isTaskParty(String objectKey, Long userId) {
        List<SubstituteTask> tasks = substituteTaskMapper.selectList(new LambdaQueryWrapper<SubstituteTask>()
                .eq(SubstituteTask::getFulfillPhotoKey, objectKey)
                .last("limit 1"));
        if (tasks.isEmpty()) {
            return false;
        }
        SubstituteTask task = tasks.get(0);
        if (Objects.equals(userId, task.getPublisherId())) {
            return true;
        }
        Long acceptedId = task.getAcceptedApplicationId();
        if (acceptedId == null) {
            return false;
        }
        SubstituteApplication application = substituteApplicationMapper.selectById(acceptedId);
        return application != null && Objects.equals(userId, application.getApplicantId());
    }

    private static Long ownerIdOf(String objectKey) {
        String[] parts = objectKey.split("/");
        if (parts.length < 2) {
            return null;
        }
        try {
            return Long.valueOf(parts[1]);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String folderOf(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return null;
        }
        int slash = objectKey.indexOf('/');
        String folder = slash < 0 ? objectKey : objectKey.substring(0, slash);
        return folder.isEmpty() ? null : folder.toLowerCase(Locale.ROOT);
    }
}
