package com.kean.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 匿名可达路径的回归保护。
 *
 * <p>此前有过两个真实问题，都由这张"匿名路径名单"引起，所以值得钉住：
 * <ul>
 *   <li>探活端点必须免鉴权 —— 探针不会带 token。</li>
 *   <li>名单必须与 {@code SecurityConfig} 的 URL 规则一致。历史上这里只认
 *       {@code /api/tasks/\d+}，而 SecurityConfig 放行的是 {@code /api/tasks/*}，
 *       于是 {@code /api/tasks/abc} 在过滤器就被判 401，前端误当成"登录已失效"
 *       把用户踢回登录页。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthFilter 的匿名路径判定")
class AnonymousPathAccessTest {

    @Mock
    private JwtService jwtService;

    @Mock
    private TokenBlacklistService tokenBlacklistService;

    @Mock
    private TokenRevokeService tokenRevokeService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    private JwtAuthFilter filter;
    private boolean chainInvoked;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthFilter(jwtService, tokenBlacklistService, tokenRevokeService, new ObjectMapper());
        chainInvoked = false;
    }

    @Test
    @DisplayName("无 token 访问 /health 与 /health/ready 应放行")
    void healthEndpointsAllowAnonymous() throws Exception {
        assertThat(passThroughWithNoToken("GET", "/health")).isTrue();
        assertThat(passThroughWithNoToken("GET", "/health/ready")).isTrue();
    }

    @Test
    @DisplayName("非数字任务 id 也应放行，交给控制器返回 400 而不是在这里判 401")
    void nonNumericTaskIdIsNotUnauthorized() throws Exception {
        assertThat(passThroughWithNoToken("GET", "/api/tasks/abc")).isTrue();
        assertThat(passThroughWithNoToken("GET", "/api/tasks/42")).isTrue();
    }

    @Test
    @DisplayName("多段任务子路径不应被误放行")
    void nestedTaskPathStillRequiresToken() throws Exception {
        assertThat(passThroughWithNoToken("GET", "/api/tasks/42/applications")).isFalse();
    }

    @Test
    @DisplayName("需要登录的接口在无 token 时仍返回 401")
    void protectedEndpointSendsUnauthorized() throws Exception {
        assertThat(passThroughWithNoToken("GET", "/api/me")).isFalse();
        verify(response).setStatus(401);
    }

    private boolean passThroughWithNoToken(String method, String path) throws Exception {
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(path);
        when(request.getContextPath()).thenReturn("");
        when(request.getHeader("Authorization")).thenReturn(null);

        StringWriter body = new StringWriter();
        // 只有 401 分支才会写响应体，放行的用例用不到 —— 用 lenient 避免 UnnecessaryStubbing。
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(body));

        FilterChain chain = (req, res) -> chainInvoked = true;
        chainInvoked = false;
        filter.doFilter(request, response, chain);
        return chainInvoked;
    }
}
