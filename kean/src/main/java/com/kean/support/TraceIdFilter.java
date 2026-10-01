package com.kean.support;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 给每个请求分配一个可追踪的 ID，写进日志的 MDC 并回显在响应头。
 *
 * <p>在此之前，线上出问题时只能靠"用户说打不开"去翻日志猜是哪一次请求 ——
 * 日志之间没有任何能关联同一请求的东西。有了它，用户/前端报出 {@code X-Request-Id}，
 * 就能直接在日志里定位那一条链路。
 *
 * <p>两个细节：
 * <ul>
 *   <li>只接受符合格式的入站 ID（长度与字符集都受限），其它一律重新生成 ——
 *       否则任意 header 内容都会被写进日志，既可能污染日志也可能撑大日志行。</li>
 *   <li>结束时必须清 MDC：Tomcat 复用线程，不清的话下一个请求会带着上一个的 ID。</li>
 * </ul>
 *
 * <p>前端要读取这个响应头，必须在 CORS 里显式 exposed（见 SecurityConfig）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "traceId";

    /** 允许的入站 ID 形态：8~64 位的字母、数字、下划线、连字符。 */
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = resolve(request.getHeader(HEADER));
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    static String resolve(String incoming) {
        if (incoming != null && SAFE.matcher(incoming).matches()) {
            return incoming;
        }
        return newTraceId();
    }

    static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
