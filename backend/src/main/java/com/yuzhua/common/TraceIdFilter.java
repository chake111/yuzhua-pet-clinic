package com.yuzhua.common;

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
import java.util.regex.Pattern;

/**
 * 同步 MVC 请求的追踪号过滤器：按最高优先级置入 MDC，仅沿用 32 位小写 hex 的入站 X-Trace-Id，
 * 其余换新值防日志注入与日志合并，finally 内清理 MDC 避免线程复用串号（不跨异步/WS 线程传递）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    private static final Pattern FORMAT = Pattern.compile("[0-9a-f]{32}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = resolve(request.getHeader(HEADER));
        MDC.put(MDC_KEY, traceId);
        // 原因: 响应头先于后续链路写入，避免容器直接处理未捕获异常时丢失追踪号
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }

    static String resolve(String inbound) {
        return inbound != null && FORMAT.matcher(inbound).matches() ? inbound : newTraceId();
    }

    private static String newTraceId() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }
}
