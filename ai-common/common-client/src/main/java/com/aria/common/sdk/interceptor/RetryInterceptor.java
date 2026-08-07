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
 * <p>重试策略按状态码区分幂等性要求：
 * <ul>
 *   <li><b>HTTP 429（限流）</b>：对<b>所有</b>方法重试。429 表示服务端<b>未处理</b>该请求
 *       （无副作用），POST/PATCH 等写操作重试是安全且必要的——LLM/Embedding 等 POST 调用
 *       遇限流应退避重试而非直接失败，否则削弱链路可用性。</li>
 *   <li><b>HTTP 5xx（服务端错误）</b>：仅对<b>幂等</b>方法（GET/HEAD/OPTIONS/PUT/DELETE）重试。
 *       5xx 下请求可能已被部分处理，重试非幂等 POST/PATCH 有重复下单/重复扣费风险。</li>
 * </ul>
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

        int attempt = 0;
        while (shouldRetry(request, response.code()) && attempt < maxRetries) {
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

    /**
     * 判断是否应重试。
     * <ul>
     *   <li>429：对所有方法重试（服务端未处理请求，无副作用）；</li>
     *   <li>5xx：仅对幂等方法重试（非幂等 POST/PATCH 可能已被部分处理，重试有副作用风险）。</li>
     * </ul>
     */
    private boolean shouldRetry(Request request, int statusCode) {
        if (statusCode == 429) {
            return true;
        }
        if (statusCode >= 500) {
            return IDEMPOTENT_METHODS.contains(request.method().toUpperCase());
        }
        return false;
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
