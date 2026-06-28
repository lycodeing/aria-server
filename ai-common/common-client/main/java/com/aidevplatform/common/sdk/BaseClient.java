package com.aidevplatform.common.sdk;

import com.aidevplatform.common.core.util.JsonUtils;
import com.aidevplatform.common.sdk.exception.SdkException;
import com.aidevplatform.common.sdk.exception.SdkNetworkException;
import com.aidevplatform.common.sdk.exception.SdkRateLimitException;
import com.aidevplatform.common.sdk.interceptor.AkSkSigningInterceptor;
import com.aidevplatform.common.sdk.interceptor.RetryInterceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * 所有 SDK Client 的基类。
 * <p>封装 OkHttp 调用，自动附加 AK/SK 签名、指数退避重试。
 * 各服务 SDK（RequirementClient、CodeClient、PipelineClient 等）继承此类。
 */
public abstract class BaseClient {

    protected final ClientConfig config;
    protected final OkHttpClient httpClient;

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    protected BaseClient(ClientConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(config.getConnectTimeout())
                .readTimeout(config.getReadTimeout())
                .addInterceptor(new AkSkSigningInterceptor(config))
                .addInterceptor(new RetryInterceptor(config.getMaxRetries()))
                .build();
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
     * 执行 HTTP 请求并解析响应。
     */
    private <T> T execute(Request request, Class<T> responseType) {
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "";
                if (response.code() == 429) {
                    throw new SdkRateLimitException(body);
                }
                throw SdkException.fromResponse(response.code(), body);
            }
            if (responseType == Void.class || responseType == void.class) {
                return null;
            }
            String body = response.body() != null ? response.body().string() : "{}";
            return JsonUtils.parseObject(body, responseType);
        } catch (IOException e) {
            throw new SdkNetworkException("网络请求失败: " + request.url(), e);
        }
    }

    public ClientConfig getConfig() {
        return config;
    }
}
