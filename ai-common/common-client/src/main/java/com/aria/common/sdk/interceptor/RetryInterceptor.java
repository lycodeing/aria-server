package com.aria.common.sdk.interceptor;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;

/**
 * 指数退避重试拦截器。
 * <p>对 HTTP 429（限流）和 5xx（服务端错误）自动重试，最多 maxRetries 次。
 * 退避间隔：5s / 15s / 45s（3^attempt × 5s）。
 */
public class RetryInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(RetryInterceptor.class);

    private final int maxRetries;

    public RetryInterceptor(int maxRetries) {
        this.maxRetries = Math.max(0, maxRetries);
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        Response response = chain.proceed(request);

        int attempt = 0;
        while (shouldRetry(response.code()) && attempt < maxRetries) {
            Duration delay = backoff(attempt);
            log.warn("请求失败 HTTP {}，{}ms 后重试 ({}/{})，url={}",
                    response.code(), delay.toMillis(), attempt + 1, maxRetries,
                    request.url());
            response.close();
            try {
                Thread.sleep(delay.toMillis());
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

    private Duration backoff(int attempt) {
        long seconds = (long) (5 * Math.pow(3, attempt));
        return Duration.ofSeconds(seconds);
    }
}
