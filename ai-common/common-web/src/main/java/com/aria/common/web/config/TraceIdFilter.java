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
import java.util.regex.Pattern;

/**
 * 全链路 traceId 注入过滤器。
 *
 * <p>运行在 Micrometer ObservationFilter（HIGHEST_PRECEDENCE + 1）之后，
 * 优先读取 Brave 已注入到 MDC 的 traceId（十六进制格式），
 * 不存在时降级为自生成 UUID（覆盖非 HTTP 入口场景）。
 *
 * <p>响应头 {@value #HEADER_TRACE_ID} 始终回写最终生效的 traceId，
 * 供调用方做端到端追踪关联。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    /** 合法 traceId 格式：字母、数字、连字符，长度 8~64，防止日志污染和响应头注入 */
    private static final Pattern TRACE_ID_PATTERN =
            Pattern.compile("^[a-zA-Z0-9\\-]{8,64}$");

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        // 优先使用 Micrometer/Brave 已注入到 MDC 的 traceId
        String traceId = MDC.get(R.MDC_TRACE_ID);
        boolean selfGenerated = false;

        if (traceId == null || traceId.isBlank()) {
            // Brave 不可用时（如单元测试、非 observe 路径），尝试复用调用方传入的 X-Trace-Id
            String incoming = request.getHeader(HEADER_TRACE_ID);
            traceId = (incoming != null && TRACE_ID_PATTERN.matcher(incoming).matches())
                    ? incoming
                    : UUID.randomUUID().toString().replace("-", "");
            MDC.put(R.MDC_TRACE_ID, traceId);
            selfGenerated = true;
        }

        response.setHeader(HEADER_TRACE_ID, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 仅清理自己写入的 MDC key，不干扰 Brave 管理的 scope
            if (selfGenerated) {
                MDC.remove(R.MDC_TRACE_ID);
            }
        }
    }
}
