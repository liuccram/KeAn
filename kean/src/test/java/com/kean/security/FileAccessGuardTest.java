package com.kean.security;

import com.kean.entity.ChatMessage;
import com.kean.entity.ChatSession;
import com.kean.entity.SubstituteApplication;
import com.kean.entity.SubstituteTask;
import com.kean.mapper.ChatMessageMapper;
import com.kean.mapper.ChatSessionMapper;
import com.kean.mapper.SubstituteApplicationMapper;
import com.kean.mapper.SubstituteTaskMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 文件读取归属校验的授权矩阵。
 *
 * <p>纯单元测试：只用 Mockito 替换 4 个 mapper，不加载 Spring 上下文、不连数据库。</p>
 */
@ExtendWith(MockitoExtension.class)
class FileAccessGuardTest {

    private static final Long OWNER = 42L;
    private static final Long OTHER = 99L;
    private static final boolean ADMIN = true;
    private static final boolean NOT_ADMIN = false;

    @Mock
    private ChatMessageMapper chatMessageMapper;

    @Mock
    private ChatSessionMapper chatSessionMapper;

    @Mock
    private SubstituteTaskMapper substituteTaskMapper;

    @Mock
    private SubstituteApplicationMapper substituteApplicationMapper;

    @InjectMocks
    private FileAccessGuard guard;

    @Test
    @DisplayName("头像与封面对匿名公开")
    void publicFoldersAreOpenToAnonymous() {
        assertThat(guard.canRead("avatar/42/x.jpg", null, NOT_ADMIN)).isTrue();
        assertThat(guard.canRead("cover/42/x.jpg", null, NOT_ADMIN)).isTrue();
    }

    @Test
    @DisplayName("匿名读敏感目录一律拒绝")
    void anonymousCannotReadSensitiveFolders() {
        assertThat(guard.canRead("fulfill/42/x.jpg", null, NOT_ADMIN)).isFalse();
        assertThat(guard.canRead("chat/42/x.jpg", null, NOT_ADMIN)).isFalse();
        assertThat(guard.canRead("report/42/x.jpg", null, NOT_ADMIN)).isFalse();
        assertThat(guard.canRead("appeal/42/x.jpg", null, NOT_ADMIN)).isFalse();
    }

    @Test
    @DisplayName("管理员可读已登记的敏感目录")
    void adminCanReadRegisteredSensitiveFolders() {
        assertThat(guard.canRead("fulfill/42/x.jpg", OWNER, ADMIN)).isTrue();
        assertThat(guard.canRead("report/42/x.jpg", OTHER, ADMIN)).isTrue();
        assertThat(guard.canRead("chat/42/x.jpg", OTHER, ADMIN)).isTrue();
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

        assertThat(guard.canRead("chat/42/x.jpg", OWNER, NOT_ADMIN)).isTrue();
        assertThat(guard.canRead("chat/42/x.jpg", OTHER, NOT_ADMIN)).isTrue();
        assertThat(guard.canRead("chat/42/x.jpg", 7L, NOT_ADMIN)).isFalse();
    }

    @Test
    @DisplayName("聊天图片：对象键不在任何消息里则拒绝")
    void chatImageWithoutMessageIsRejected() {
        when(chatMessageMapper.selectList(any())).thenReturn(List.of());

        assertThat(guard.canRead("chat/42/x.jpg", OWNER, NOT_ADMIN)).isFalse();
    }

    @Test
    @DisplayName("聊天图片：会话缺失则拒绝")
    void chatImageWithoutSessionIsRejected() {
        stubChatMessageInSession(5L);
        when(chatSessionMapper.selectById(5L)).thenReturn(null);

        assertThat(guard.canRead("chat/42/x.jpg", OWNER, NOT_ADMIN)).isFalse();
    }

    @Test
    @DisplayName("履约照片：发布者与被接受的申请人可读，无关用户拒绝")
    void fulfillPhotoOnlyTaskPartiesCanRead() {
        stubTaskWithAcceptedApplicant(OWNER, 11L);
        stubAcceptedApplicant(11L, OTHER);

        assertThat(guard.canRead("fulfill/42/x.jpg", OWNER, NOT_ADMIN)).isTrue();
        assertThat(guard.canRead("fulfill/42/x.jpg", OTHER, NOT_ADMIN)).isTrue();
        assertThat(guard.canRead("fulfill/42/x.jpg", 7L, NOT_ADMIN)).isFalse();
    }

    @Test
    @DisplayName("履约照片：未被接受的申请人不可读")
    void fulfillPhotoRejectedApplicantCannotRead() {
        stubTaskWithAcceptedApplicant(OWNER, 11L);
        stubAcceptedApplicant(11L, OTHER);

        // 7 号也申请过，但 acceptedApplicationId 指向的是 OTHER
        assertThat(guard.canRead("fulfill/42/x.jpg", 7L, NOT_ADMIN)).isFalse();
    }

    @Test
    @DisplayName("履约照片：任务尚未选出申请人时仅发布者可读")
    void fulfillPhotoWithoutAcceptedApplication() {
        SubstituteTask task = new SubstituteTask();
        task.setPublisherId(OWNER);
        task.setAcceptedApplicationId(null);
        when(substituteTaskMapper.selectList(any())).thenReturn(List.of(task));

        assertThat(guard.canRead("fulfill/42/x.jpg", OWNER, NOT_ADMIN)).isTrue();
        assertThat(guard.canRead("fulfill/42/x.jpg", OTHER, NOT_ADMIN)).isFalse();
    }

    @Test
    @DisplayName("履约照片：对象键查不到任务则拒绝")
    void fulfillPhotoWithoutTaskIsRejected() {
        when(substituteTaskMapper.selectList(any())).thenReturn(List.of());

        assertThat(guard.canRead("fulfill/42/x.jpg", OWNER, NOT_ADMIN)).isFalse();
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
