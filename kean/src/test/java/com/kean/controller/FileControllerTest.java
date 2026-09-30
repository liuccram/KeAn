package com.kean.controller;

import com.kean.security.FileAccessGuard;
import com.kean.security.FileUrlSigner;
import com.kean.security.LoginUser;
import com.kean.service.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 文件下载的放行 / 拒绝判定。
 *
 * <p>纯单元测试：直接调用 {@code download}，用 MockHttpServletResponse 观察状态码，
 * 不加载 Spring 上下文。{@code FileAccessGuard} 在这里被替换为替身，
 * 所以本测试只负责"控制器把结果正确映射成 200/403"；授权规则本身由
 * {@link com.kean.security.FileAccessGuardTest} 覆盖，两者不可互相替代。</p>
 */
@ExtendWith(MockitoExtension.class)
class FileControllerTest {

    private static final String KEY = "fulfill/42/x.jpg";
    private static final String SIGNED_EXP = "1893456000";
    private static final String SIGNED_SIG = "a1b2c3";
    private static final Long OWNER = 42L;
    private static final Long STRANGER = 7L;
    private static final Long ADMIN_ID = 1L;
    private static final String STUDENT_ROLE = "STUDENT";
    private static final String ADMIN_ROLE = "ADMIN";

    @Mock
    private StorageService storageService;

    @Mock
    private FileUrlSigner fileUrlSigner;

    @Mock
    private FileAccessGuard fileAccessGuard;

    private FileController controller;

    @BeforeEach
    void setUp() {
        controller = new FileController(storageService, fileUrlSigner, fileAccessGuard);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("签名有效即放行，且不再做归属查询（匿名 img 场景）")
    void validSignatureIsServedWithoutOwnershipLookup() throws Exception {
        when(fileUrlSigner.verify(KEY, SIGNED_EXP, SIGNED_SIG)).thenReturn(true);
        stubServing(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.download(KEY, SIGNED_EXP, SIGNED_SIG, response);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(fileAccessGuard, never()).canRead(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("匿名且签名无效时拒绝（不再因为「已登录」就放行）")
    void anonymousWithoutSignatureIsForbidden() throws Exception {
        when(fileUrlSigner.verify(KEY, null, null)).thenReturn(false);
        when(fileAccessGuard.canRead(KEY, null, false)).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.download(KEY, null, null, response);

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("已登录但无归属关系时仍拒绝")
    void loggedInButNotEntitledIsForbidden() throws Exception {
        loginAs(STRANGER, STUDENT_ROLE);
        when(fileUrlSigner.verify(KEY, null, null)).thenReturn(false);
        when(fileAccessGuard.canRead(KEY, STRANGER, false)).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.download(KEY, null, null, response);

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("有归属关系的用户可以不带签名读取")
    void entitledUserIsServedWithoutSignature() throws Exception {
        loginAs(OWNER, STUDENT_ROLE);
        when(fileUrlSigner.verify(KEY, null, null)).thenReturn(false);
        when(fileAccessGuard.canRead(KEY, OWNER, false)).thenReturn(true);
        stubServing(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.download(KEY, null, null, response);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("管理员身份会正确传递给归属校验")
    void adminRoleIsForwardedToGuard() throws Exception {
        loginAs(ADMIN_ID, ADMIN_ROLE);
        when(fileUrlSigner.verify(KEY, null, null)).thenReturn(false);
        when(fileAccessGuard.canRead(KEY, ADMIN_ID, true)).thenReturn(true);
        stubServing(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.download(KEY, null, null, response);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(fileAccessGuard).canRead(KEY, ADMIN_ID, true);
    }

    @Test
    @DisplayName("含路径穿越的对象键直接 404，且不触碰签名与归属校验")
    void unsafeObjectKeyIsRejectedEarly() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.download("../etc/passwd", null, null, response);

        assertThat(response.getStatus()).isEqualTo(404);
        verifyNoInteractions(fileUrlSigner);
        verifyNoInteractions(fileAccessGuard);
        verifyNoInteractions(storageService);
    }

    @Test
    @DisplayName("敏感目录返回 private 缓存头")
    void sensitiveKeyGetsPrivateCacheControl() throws Exception {
        when(fileUrlSigner.verify(KEY, SIGNED_EXP, SIGNED_SIG)).thenReturn(true);
        stubServing(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.download(KEY, SIGNED_EXP, SIGNED_SIG, response);

        assertThat(response.getHeader("Cache-Control")).isEqualTo("private, max-age=300");
    }

    @Test
    @DisplayName("公开目录返回 public 缓存头")
    void publicKeyGetsPublicCacheControl() throws Exception {
        when(fileUrlSigner.verify(KEY, SIGNED_EXP, SIGNED_SIG)).thenReturn(true);
        stubServing(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.download(KEY, SIGNED_EXP, SIGNED_SIG, response);

        assertThat(response.getHeader("Cache-Control")).isEqualTo("public, max-age=86400");
    }

    @Test
    @DisplayName("对象存储读取失败时返回 404")
    void storageFailureBecomesNotFound() throws Exception {
        when(fileUrlSigner.verify(KEY, SIGNED_EXP, SIGNED_SIG)).thenReturn(true);
        when(storageService.open(KEY)).thenThrow(new IllegalStateException("对象存储不可用"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.download(KEY, SIGNED_EXP, SIGNED_SIG, response);

        assertThat(response.getStatus()).isEqualTo(404);
    }

    private void loginAs(Long userId, String role) {
        LoginUser loginUser = new LoginUser(userId, "user" + userId, role, "jti-" + userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, List.of())
        );
    }

    private void stubServing(boolean sensitive) {
        when(storageService.open(KEY))
                .thenReturn(new ByteArrayInputStream("image-bytes".getBytes(StandardCharsets.UTF_8)));
        when(storageService.contentType(KEY)).thenReturn("image/jpeg");
        when(fileUrlSigner.isSensitive(KEY)).thenReturn(sensitive);
    }
}
