package com.aria.common.sdk.interceptor;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.MDC;

import java.io.IOException;

/**
 * OkHttp 拦截器：将当前线程 MDC 中的 traceId 透传到下游服务请求头。
 *
 * <p>挂载到 {@link com.aria.common.sdk.BaseClient} 的 OkHttpClient，
 * 确保 auth-service / knowledge-service 等内部服务调用携带 {@code X-Trace-Id} 头，
 * 使全链路 traceId 在服务间保持一致。
 *
 * <p>MDC 中无 traceId 时（非 HTTP 入口场景）不附加头部，保持透明。
 */
public class TraceIdPropagationInterceptor implements Interceptor {

    /** 与 {@link com.aria.common.web.config.TraceIdFilter#HEADER_TRACE_ID} 保持一致 */
    private static final String HEADER_TRACE_ID = "X-Trace-Id";
    /** 与 {@link com.aria.common.web.response.R#MDC_TRACE_ID} 保持一致 */
    private static final String MDC_KEY = "traceId";

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request original = chain.request();
        String traceId = MDC.get(MDC_KEY);
        if (traceId == null || traceId.isBlank()) {
            return chain.proceed(original);
        }
        Request withTrace = original.newBuilder()
                .header(HEADER_TRACE_ID, traceId)
                .build();
        return chain.proceed(withTrace);
    }
}
