package com.kean.security;

import com.github.benmanes.caffeine.cache.Ticker;
import com.kean.entity.ChatMessage;
import com.kean.entity.ChatSession;
import com.kean.entity.SubstituteApplication;
import com.kean.entity.SubstituteTask;
import com.kean.mapper.ChatMessageMapper;
import com.kean.mapper.ChatSessionMapper;
import com.kean.mapper.SubstituteApplicationMapper;
import com.kean.mapper.SubstituteTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文件读取归属校验的授权矩阵与短 TTL 缓存。
 *
 * <p>纯单元测试：只用 Mockito 替换 4 个 mapper，不加载 Spring 上下文、不连数据库。
 * 时钟通过 {@link FakeTicker} 注入，以便验证缓存过期行为而不用真的等待。</p>
 */
@ExtendWith(MockitoExtension.class)
class FileAccessGuardTest {

    private static final Long OWNER = 42L;
    private static final Long OTHER = 99L;
    private static final Long STRANGER = 7L;
    private static final boolean ADMIN = true;
    private static final boolean NOT_ADMIN = false;
    private static final String CHAT_KEY = "chat/42/x.jpg";
    private static final String FULFILL_KEY = "fulfill/42/x.jpg";

    @Mock
    private ChatMessageMapper chatMessageMapper;

    @Mock
    private ChatSessionMapper chatSessionMapper;

    @Mock
    private SubstituteTaskMapper substituteTaskMapper;

    @Mock
    private SubstituteApplicationMapper substituteApplicationMapper;

    private final FakeTicker ticker = new FakeTicker();

    private FileAccessGuard guard;

    @BeforeEach
    void setUp() {
        guard = new FileAccessGuard(
                chatMessageMapper, chatSessionMapper, substituteTaskMapper, substituteApplicationMapper, ticker);
    }

    // ---------- 授权矩阵 ----------

    @Test
    @DisplayName("头像与封面对匿名公开")
    void publicFoldersAreOpenToAnonymous() {
        assertThat(guard.canRead("avatar/42/x.jpg", null, NOT_ADMIN)).isTrue();
        assertThat(guard.canRead("cover/42/x.jpg", null, NOT_ADMIN)).isTrue();
    }

    @Test
    @DisplayName("匿名读敏感目录一律拒绝")
    void anonymousCannotReadSensitiveFolders() {
        assertThat(guard.canRead(FULFILL_KEY, null, NOT_ADMIN)).isFalse();
        assertThat(guard.canRead(CHAT_KEY, null, NOT_ADMIN)).isFalse();
        assertThat(guard.canRead("report/42/x.jpg", null, NOT_ADMIN)).isFalse();
        assertThat(guard.canRead("appeal/42/x.jpg", null, NOT_ADMIN)).isFalse();
    }

    @Test
    @DisplayName("管理员可读已登记的敏感目录")
    void adminCanReadRegisteredSensitiveFolders() {
        assertThat(guard.canRead(FULFILL_KEY, OWNER, ADMIN)).isTrue();
        assertThat(guard.canRead("report/42/x.jpg", OTHER, ADMIN)).isTrue();
        assertThat(guard.canRead(CHAT_KEY, OTHER, ADMIN)).isTrue();
    }

    @Test
    @DisplayName("未知目录默认拒绝，管理员也不放行")
    void unknownFolderIsDeniedEvenForAdmin() {
        assertThat(guard.canRead("secret/42/x.jpg", OWNER, NOT_ADMIN)).isFalse();
        assertThat(guard.canRead("secret/42/x.jpg", OWNER, ADMIN)).isFalse();
        assertThat(guard.canRead("avatar2/42/x.jpg", OWNER, ADMIN)).isFalse();
    }

    @Test
    @DisplayName("非法或空的对象键一律拒绝")
    void blankKeyIsRejected() {
        assertThat(guard.canRead(null, OWNER, ADMIN)).isFalse();
        assertThat(guard.canRead("", OWNER, ADMIN)).isFalse();
        assertThat(guard.canRead("   ", OWNER, ADMIN)).isFalse();
    }

    @Test
    @DisplayName("举报图片：仅上传者本人可读")
    void reportImageOnlyOwnerCanRead() {
        assertThat(guard.canRead("report/42/x.jpg", OWNER, NOT_ADMIN)).isTrue();
        assertThat(guard.canRead("report/42/x.jpg", OTHER, NOT_ADMIN)).isFalse();
    }

    @Test
    @DisplayName("申诉图片：仅上传者本人可读")
    void appealImageOnlyOwnerCanRead() {
        assertThat(guard.canRead("appeal/42/x.jpg", OWNER, NOT_ADMIN)).isTrue();
        assertThat(guard.canRead("appeal/42/x.jpg", OTHER, NOT_ADMIN)).isFalse();
    }

    @Test
    @DisplayName("对象键缺少或非法的用户段时，报告类目录拒绝")
    void ownerScopedFolderWithoutUserIdSegmentIsRejected() {
        assertThat(guard.canRead("report/x.jpg", OWNER, NOT_ADMIN)).isFalse();
        assertThat(guard.canRead("report/not-a-number/x.jpg", OWNER, NOT_ADMIN)).isFalse();
    }

    @Test
    @DisplayName("聊天图片：仅会话双方可读")
    void chatImageOnlyParticipantsCanRead() {
        stubChatMessageInSession(5L);
        stubSession(5L, OWNER, OTHER);

        assertThat(guard.canRead(CHAT_KEY, OWNER, NOT_ADMIN)).isTrue();
        assertThat(guard.canRead(CHAT_KEY, OTHER, NOT_ADMIN)).isTrue();
        assertThat(guard.canRead(CHAT_KEY, STRANGER, NOT_ADMIN)).isFalse();
    }

    @Test
    @DisplayName("聊天图片：对象键不在任何消息里则拒绝")
    void chatImageWithoutMessageIsRejected() {
        when(chatMessageMapper.selectList(any())).thenReturn(List.of());

        assertThat(guard.canRead(CHAT_KEY, OWNER, NOT_ADMIN)).isFalse();
    }

    @Test
    @DisplayName("聊天图片：会话缺失则拒绝")
    void chatImageWithoutSessionIsRejected() {
        stubChatMessageInSession(5L);
        when(chatSessionMapper.selectById(5L)).thenReturn(null);

        assertThat(guard.canRead(CHAT_KEY, OWNER, NOT_ADMIN)).isFalse();
    }

    @Test
    @DisplayName("履约照片：发布者与被接受的申请人可读，无关用户拒绝")
    void fulfillPhotoOnlyTaskPartiesCanRead() {
        stubTaskWithAcceptedApplicant(OWNER, 11L);
        stubAcceptedApplicant(11L, OTHER);

        assertThat(guard.canRead(FULFILL_KEY, OWNER, NOT_ADMIN)).isTrue();
        assertThat(guard.canRead(FULFILL_KEY, OTHER, NOT_ADMIN)).isTrue();
        assertThat(guard.canRead(FULFILL_KEY, STRANGER, NOT_ADMIN)).isFalse();
    }

    @Test
    @DisplayName("履约照片：未被接受的申请人不可读")
    void fulfillPhotoRejectedApplicantCannotRead() {
        stubTaskWithAcceptedApplicant(OWNER, 11L);
        stubAcceptedApplicant(11L, OTHER);

        // STRANGER 也申请过，但 acceptedApplicationId 指向的是 OTHER
        assertThat(guard.canRead(FULFILL_KEY, STRANGER, NOT_ADMIN)).isFalse();
    }

    @Test
    @DisplayName("履约照片：任务尚未选出申请人时仅发布者可读")
    void fulfillPhotoWithoutAcceptedApplication() {
        SubstituteTask task = new SubstituteTask();
        task.setPublisherId(OWNER);
        task.setAcceptedApplicationId(null);
        when(substituteTaskMapper.selectList(any())).thenReturn(List.of(task));

        assertThat(guard.canRead(FULFILL_KEY, OWNER, NOT_ADMIN)).isTrue();
        assertThat(guard.canRead(FULFILL_KEY, OTHER, NOT_ADMIN)).isFalse();
    }

    @Test
    @DisplayName("履约照片：对象键查不到任务则拒绝")
    void fulfillPhotoWithoutTaskIsRejected() {
        when(substituteTaskMapper.selectList(any())).thenReturn(List.of());

        assertThat(guard.canRead(FULFILL_KEY, OWNER, NOT_ADMIN)).isFalse();
    }

    // ---------- 短 TTL 缓存 ----------

    @Test
    @DisplayName("同一用户重复读同一张聊天图，只查一次库")
    void repeatedReadBySameUserHitsDatabaseOnce() {
        stubChatMessageInSession(5L);
        stubSession(5L, OWNER, OTHER);

        assertThat(guard.canRead(CHAT_KEY, OWNER, NOT_ADMIN)).isTrue();
        assertThat(guard.canRead(CHAT_KEY, OWNER, NOT_ADMIN)).isTrue();
        assertThat(guard.canRead(CHAT_KEY, OWNER, NOT_ADMIN)).isTrue();

        verify(chatMessageMapper, times(1)).selectList(any());
        verify(chatSessionMapper, times(1)).selectById(5L);
    }

    @Test
    @DisplayName("拒绝结果同样被缓存，猜 key 不会每次打库")
    void deniedResultIsCachedToo() {
        when(chatMessageMapper.selectList(any())).thenReturn(List.of());

        assertThat(guard.canRead(CHAT_KEY, OWNER, NOT_ADMIN)).isFalse();
        assertThat(guard.canRead(CHAT_KEY, OWNER, NOT_ADMIN)).isFalse();

        verify(chatMessageMapper, times(1)).selectList(any());
    }

    @Test
    @DisplayName("不同用户各自独立判定，不共享缓存条目")
    void differentUsersDoNotShareCacheEntry() {
        stubChatMessageInSession(5L);
        stubSession(5L, OWNER, OTHER);

        assertThat(guard.canRead(CHAT_KEY, OWNER, NOT_ADMIN)).isTrue();
        assertThat(guard.canRead(CHAT_KEY, STRANGER, NOT_ADMIN)).isFalse();

        verify(chatMessageMapper, times(2)).selectList(any());
    }

    @Test
    @DisplayName("履约照片的归属判定同样走缓存")
    void fulfillDecisionIsCached() {
        stubTaskWithAcceptedApplicant(OWNER, 11L);

        assertThat(guard.canRead(FULFILL_KEY, OWNER, NOT_ADMIN)).isTrue();
        assertThat(guard.canRead(FULFILL_KEY, OWNER, NOT_ADMIN)).isTrue();

        // 发布者判定在查任务之后立即返回，不会再去查申请
        verify(substituteTaskMapper, times(1)).selectList(any());
        verify(substituteApplicationMapper, times(0)).selectById(any());
    }

    @Test
    @DisplayName("TTL 过期后重新查库，新建立的关系能及时生效")
    void cacheExpiresAfterTtl() {
        ChatMessage message = new ChatMessage();
        message.setSessionId(5L);
        ChatSession session = new ChatSession();
        session.setUserAId(OWNER);
        session.setUserBId(OTHER);

        // 第 1 次查库：还没有任何消息 → 拒绝
        // TTL 过期后第 2 次查库：会话已建立 → 放行
        when(chatMessageMapper.selectList(any()))
                .thenReturn(List.<ChatMessage>of(), List.of(message));
        when(chatSessionMapper.selectById(5L)).thenReturn(session);

        assertThat(guard.canRead(CHAT_KEY, OWNER, NOT_ADMIN)).isFalse();

        // 30 秒后仍在 TTL 内：继续用缓存里的拒绝结果
        ticker.advance(Duration.ofSeconds(30));
        assertThat(guard.canRead(CHAT_KEY, OWNER, NOT_ADMIN)).isFalse();

        // 累计 50 秒 > 45 秒 TTL：重新查库，拿到新建的会话
        ticker.advance(Duration.ofSeconds(20));
        assertThat(guard.canRead(CHAT_KEY, OWNER, NOT_ADMIN)).isTrue();

        verify(chatMessageMapper, times(2)).selectList(any());
    }

    // ---------- 测试替身与辅助方法 ----------

    /** 可控时钟：Caffeine 通过它取时间，从而不必真的等待。 */
    private static final class FakeTicker implements Ticker {

        /** 从非零起算，避免任何把 0 当作哨兵值的边界问题。 */
        private long nanos = Duration.ofSeconds(1).toNanos();

        @Override
        public long read() {
            return nanos;
        }

        void advance(Duration duration) {
            nanos += duration.toNanos();
        }
    }

    private void stubChatMessageInSession(Long sessionId) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        when(chatMessageMapper.selectList(any())).thenReturn(List.of(message));
    }

    private void stubSession(Long sessionId, Long userA, Long userB) {
        ChatSession session = new ChatSession();
        session.setUserAId(userA);
        session.setUserBId(userB);
        when(chatSessionMapper.selectById(sessionId)).thenReturn(session);
    }

    private void stubTaskWithAcceptedApplicant(Long publisherId, Long acceptedApplicationId) {
        SubstituteTask task = new SubstituteTask();
        task.setPublisherId(publisherId);
        task.setAcceptedApplicationId(acceptedApplicationId);
        when(substituteTaskMapper.selectList(any())).thenReturn(List.of(task));
    }

    private void stubAcceptedApplicant(Long applicationId, Long applicantId) {
        SubstituteApplication application = new SubstituteApplication();
        application.setId(applicationId);
        application.setApplicantId(applicantId);
        when(substituteApplicationMapper.selectById(applicationId)).thenReturn(application);
    }
}
