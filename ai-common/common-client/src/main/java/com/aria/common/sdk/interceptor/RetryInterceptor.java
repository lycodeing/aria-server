package com.aria.common.sdk.interceptor;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 指数退避重试拦截器。
 * <p>仅对<b>幂等</b>方法（GET/HEAD/OPTIONS/PUT/DELETE）的 HTTP 429（限流）和 5xx（服务端错误）
 * 自动重试，最多 maxRetries 次；POST/PATCH 等非幂等方法不重试，避免重复下单/重复扣费。
 * <p>退避间隔：指数退避 + 随机抖动，单次上限 {@value #MAX_DELAY_MS}ms；
 * 若响应带 {@code Retry-After} 头则优先尊重（同样受上限约束）。
 */
public class RetryInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(RetryInterceptor.class);

    /** 幂等方法白名单：仅这些方法允许自动重试 */
    private static final Set<String> IDEMPOTENT_METHODS =
            Set.of("GET", "HEAD", "OPTIONS", "PUT", "DELETE");

    /** 退避基数（毫秒） */
    private static final long BASE_DELAY_MS = 500L;
    /** 单次退避上限（毫秒），防止单请求长时间阻塞 OkHttp 工作线程 */
    private static final long MAX_DELAY_MS = 8000L;

    private final int maxRetries;

    public RetryInterceptor(int maxRetries) {
        this.maxRetries = Math.max(0, maxRetries);
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        Response response = chain.proceed(request);

        // 非幂等方法（POST/PATCH 等）不重试，避免副作用被重复执行
        if (!IDEMPOTENT_METHODS.contains(request.method().toUpperCase())) {
            return response;
        }

        int attempt = 0;
        while (shouldRetry(response.code()) && attempt < maxRetries) {
            long delayMs = resolveDelay(response, attempt);
            log.warn("请求失败 HTTP {}，{}ms 后重试 ({}/{})，method={} url={}",
                    response.code(), delayMs, attempt + 1, maxRetries,
                    request.method(), request.url());
            response.close();
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("重试等待被中断", e);
            }
            response = chain.proceed(request);
            attempt++;
        }
        return response;
    }

    private boolean shouldRetry(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    /**
     * 计算本次重试的等待时长（毫秒）：优先取 {@code Retry-After} 头（秒），
     * 否则指数退避 + 随机抖动；两者都受 {@link #MAX_DELAY_MS} 上限约束。
     */
    private long resolveDelay(Response response, int attempt) {
        String retryAfter = response.header("Retry-After");
        if (retryAfter != null && !retryAfter.isBlank()) {
            try {
                long seconds = Long.parseLong(retryAfter.trim());
                if (seconds >= 0) {
                    return Math.min(seconds * 1000L, MAX_DELAY_MS);
                }
            } catch (NumberFormatException ignored) {
                // 非数字（HTTP-date 格式）时忽略，走指数退避
            }
        }
        long exp = Math.min(BASE_DELAY_MS * (1L << attempt), MAX_DELAY_MS);
        // 加 0~50% 随机抖动，避免多客户端同步重试造成惊群
        long jitter = ThreadLocalRandom.current().nextLong(exp / 2 + 1);
        return Math.min(exp + jitter, MAX_DELAY_MS);
    }
}
