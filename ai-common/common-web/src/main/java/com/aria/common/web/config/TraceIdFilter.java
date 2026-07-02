package com.aria.common.web.config;

import com.aria.common.web.response.R;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    /** 合法 traceId 格式：字母、数字、连字符，长度 8~64，防止日志污染和响应头注入 */
    private static final java.util.regex.Pattern TRACE_ID_PATTERN =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9\\-]{8,64}$");

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(HEADER_TRACE_ID);
        // 校验格式：不合法时忽略前端传入值，服务端自行生成，防止日志污染和响应头注入
        if (traceId == null || traceId.isBlank() || !TRACE_ID_PATTERN.matcher(traceId).matches()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put(R.MDC_TRACE_ID, traceId);
        response.setHeader(HEADER_TRACE_ID, traceId);
        try { filterChain.doFilter(request, response); }
        finally { MDC.remove(R.MDC_TRACE_ID); }
    }
}
