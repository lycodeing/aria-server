package com.aria.common.sdk;

import com.aria.common.core.util.JsonUtils;
import com.aria.common.sdk.exception.SdkException;
import com.aria.common.sdk.exception.SdkNetworkException;
import com.aria.common.sdk.exception.SdkRateLimitException;
import com.aria.common.sdk.interceptor.AkSkSigningInterceptor;
import com.aria.common.sdk.interceptor.RetryInterceptor;
import com.aria.common.sdk.interceptor.SharedSecretInterceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.Map;

/**
 * 所有 SDK Client 的基类。
 * <p>封装 OkHttp 调用，按 {@link com.aria.common.sdk.auth.AuthMode} 自动装配鉴权拦截器，
 * 并附带指数退避重试。各服务 SDK（KnowledgeClient、AuthClient 等）继承此类。
 */
public abstract class BaseClient {

    protected final ClientConfig config;
    protected final OkHttpClient httpClient;

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    protected BaseClient(ClientConfig config) {
        this.config = config;
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(config.getConnectTimeout())
                .readTimeout(config.getReadTimeout());
        // 鉴权拦截器按模式分派；NONE 模式不装配任何鉴权拦截器
        switch (config.getAuthMode()) {
            case AK_SK -> builder.addInterceptor(new AkSkSigningInterceptor(config));
            case SHARED_SECRET -> builder.addInterceptor(new SharedSecretInterceptor(config.getSharedSecret()));
            case NONE -> { /* 无鉴权，仅测试或健康探针使用 */ }
        }
        builder.addInterceptor(new RetryInterceptor(config.getMaxRetries()));
        this.httpClient = builder.build();
    }

    /**
     * GET 请求。
     */
    protected <T> T get(String path, Class<T> responseType) {
        Request request = new Request.Builder()
                .url(config.url(path))
                .get()
                .build();
        return execute(request, responseType);
    }

    /**
     * GET 请求（泛型响应类型，用于解析 {@code R<T>} 等参数化类型）。
     */
    protected <T> T get(String path, TypeRef<T> responseType) {
        Request request = new Request.Builder()
                .url(config.url(path))
                .get()
                .build();
        return execute(request, responseType);
    }

    /**
     * GET 请求（带查询参数）。
     */
    protected <T> T get(String path, Map<String, Object> params, Class<T> responseType) {
        okhttp3.HttpUrl.Builder urlBuilder = okhttp3.HttpUrl.parse(config.url(path)).newBuilder();
        if (params != null) {
            params.forEach((k, v) -> urlBuilder.addQueryParameter(k, String.valueOf(v)));
        }
        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .get()
                .build();
        return execute(request, responseType);
    }

    /**
     * POST 请求（JSON body）。
     */
    protected <T> T post(String path, Object body, Class<T> responseType) {
        RequestBody requestBody = RequestBody.create(JsonUtils.toJsonString(body), JSON);
        Request request = new Request.Builder()
                .url(config.url(path))
                .post(requestBody)
                .build();
        return execute(request, responseType);
    }

    /**
     * POST 请求（泛型响应类型）。
     */
    protected <T> T post(String path, Object body, TypeRef<T> responseType) {
        RequestBody requestBody = RequestBody.create(JsonUtils.toJsonString(body), JSON);
        Request request = new Request.Builder()
                .url(config.url(path))
                .post(requestBody)
                .build();
        return execute(request, responseType);
    }

    /**
     * PUT 请求（JSON body）。
     */
    protected <T> T put(String path, Object body, Class<T> responseType) {
        RequestBody requestBody = RequestBody.create(JsonUtils.toJsonString(body), JSON);
        Request request = new Request.Builder()
                .url(config.url(path))
                .put(requestBody)
                .build();
        return execute(request, responseType);
    }

    /**
     * PUT 请求（无 body，如状态流转端点）。
     */
    protected void put(String path, Object body) {
        RequestBody requestBody = body != null
                ? RequestBody.create(JsonUtils.toJsonString(body), JSON)
                : RequestBody.create("", JSON);
        Request request = new Request.Builder()
                .url(config.url(path))
                .put(requestBody)
                .build();
        execute(request, Void.class);
    }

    /**
     * DELETE 请求。
     */
    protected void delete(String path) {
        Request request = new Request.Builder()
                .url(config.url(path))
                .delete()
                .build();
        execute(request, Void.class);
    }

    /**
     * 执行 HTTP 请求并解析响应（Class 版本）。
     */
    private <T> T execute(Request request, Class<T> responseType) {
        String body = executeRaw(request);
        if (responseType == Void.class || responseType == void.class) {
            return null;
        }
        return JsonUtils.parseObject(body, responseType);
    }

    /**
     * 执行 HTTP 请求并解析响应（TypeRef 版本，用于泛型类型）。
     */
    private <T> T execute(Request request, TypeRef<T> responseType) {
        String body = executeRaw(request);
        return JsonUtils.parseObject(body, responseType);
    }

    /**
     * 底层执行，返回原始 body 字符串；Void 场景返回 null。
     */
    private String executeRaw(Request request) {
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                if (response.code() == 429) {
                    throw new SdkRateLimitException(body);
                }
                throw SdkException.fromResponse(response.code(), body);
            }
            return body.isEmpty() ? "{}" : body;
        } catch (IOException e) {
            throw new SdkNetworkException("网络请求失败: " + request.url(), e);
        }
    }

    public ClientConfig getConfig() {
        return config;
    }
}
