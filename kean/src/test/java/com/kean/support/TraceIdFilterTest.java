package com.kean.support;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("请求追踪 ID")
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    @DisplayName("没有入站 ID 时生成一个，回显在响应头，并在请求结束后清掉 MDC")
    void generatesWhenAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/schools");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seenInChain = new String[1];

        filter.doFilter(request, response, (req, res) -> seenInChain[0] = MDC.get(TraceIdFilter.MDC_KEY));

        assertThat(seenInChain[0]).isNotBlank();
        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo(seenInChain[0]);
        // Tomcat 复用线程：不清掉的话下一个请求会带着上一个的 ID
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("入站 ID 合法时沿用它，便于跨端串联同一次操作")
    void reusesValidIncomingId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/schools");
        request.addHeader(TraceIdFilter.HEADER, "abc-123_XYZ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo("abc-123_XYZ");
    }

    @Test
    @DisplayName("入站 ID 含空格、过短或过长时一律重新生成，避免污染日志")
    void replacesUnsafeIncomingId() {
        assertThat(TraceIdFilter.resolve("bad id with spaces")).isNotEqualTo("bad id with spaces");
        assertThat(TraceIdFilter.resolve("a".repeat(200))).isNotEqualTo("a".repeat(200));
        assertThat(TraceIdFilter.resolve("short")).isNotEqualTo("short");
        assertThat(TraceIdFilter.resolve(null)).isNotBlank();
        assertThat(TraceIdFilter.resolve("abcdefgh")).isEqualTo("abcdefgh");
    }

    @Test
    @DisplayName("请求抛异常时也要清掉 MDC")
    void clearsMdcEvenOnFailure() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/schools");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            throw new ServletException("boom");
        })).isInstanceOf(ServletException.class);

        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }
}
