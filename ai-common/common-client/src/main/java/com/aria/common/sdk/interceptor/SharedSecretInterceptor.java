package com.aria.common.sdk.interceptor;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;

/**
 * 静态共享密钥拦截器。
 *
 * <p>为每个内网请求附加 {@code X-Internal-Secret} 请求头，
 * 服务端与客户端使用同一份 secret 进行字符串比对完成鉴权。
 *
 * <p>安全约束：
 * <ul>
 *   <li>构造时对 secret 做非空校验，缺失即抛异常，防止"占位符默认值 → 运行时 403"链路。</li>
 *   <li>secret 值不写入任何日志。</li>
 *   <li>仅用于内网服务间调用，配合网关层禁止 {@code /internal/**} 外网访问。</li>
 * </ul>
 *
 * @author lycodeing
 * @since 2026-07
 */
public class SharedSecretInterceptor implements Interceptor {

    public static final String HEADER = "X-Internal-Secret";

    private final String secret;

    public SharedSecretInterceptor(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("内部共享密钥不能为空，请检查 shared-secret 配置");
        }
        this.secret = secret;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request original = chain.request();
        Request signed = original.newBuilder()
                .header(HEADER, secret)
                .build();
        return chain.proceed(signed);
    }
}
