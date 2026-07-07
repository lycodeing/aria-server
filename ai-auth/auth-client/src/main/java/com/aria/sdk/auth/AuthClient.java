package com.aria.sdk.auth;

import com.aria.common.sdk.BaseClient;
import com.aria.common.sdk.ClientConfig;
import com.aria.common.sdk.TypeRef;
import com.aria.common.sdk.exception.SdkException;
import com.aria.sdk.auth.exception.AuthClientException;
import com.aria.sdk.auth.internal.ApiResponse;
import com.aria.sdk.auth.model.AiModelConfigDTO;
import com.aria.sdk.auth.model.ModelScope;
import com.aria.sdk.auth.token.TokenVerifyRequest;
import com.aria.sdk.auth.token.TokenVerifyResult;

import java.time.Duration;

/**
 * auth-service 内网接口 SDK 门面。
 *
 * <p>封装 {@code /internal/ai-models/**} 与 {@code /api/v1/internal/token/verify}
 * 的 HTTP 协议细节，包括 {@code X-Internal-Secret} 鉴权头、URL 拼接、
 * {@code R<T>} 响应包装解析等，让上层只面向业务方法编程。
 *
 * <p>使用示例：
 * <pre>
 * // 手动构建
 * AuthClient client = AuthClient.builder()
 *     .baseUrl("http://auth-service:8083")
 *     .sharedSecret(System.getenv("ARIA_INTERNAL_SECRET"))
 *     .build();
 *
 * // Spring Boot 自动装配：application.yml 中配置 aria.auth.client.* 即可
 * {@literal @}Autowired AuthClient authClient;
 *
 * AiModelConfigDTO chat = authClient.getActiveModel(ModelScope.CHAT);
 * TokenVerifyResult vr = authClient.verifyToken(bearerToken);
 * </pre>
 *
 * @author lycodeing
 * @since 2026-07
 */
public class AuthClient extends BaseClient {

    private AuthClient(ClientConfig config) {
        super(config);
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---- AI 模型配置 ----

    /**
     * 拉取指定作用域的当前激活模型配置。
     *
     * <p>服务端返回 {@code apiKey} 为解密后明文，调用方需自行控制传播范围。
     *
     * @param scope 作用域，决定命中哪条内部接口
     * @return 强类型配置；服务端 404 或 code != 200 时抛 {@link AuthClientException}
     */
    public AiModelConfigDTO getActiveModel(ModelScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("ModelScope 不能为空");
        }
        ApiResponse<AiModelConfigDTO> resp = doGet(
                scope.path(),
                new TypeRef<ApiResponse<AiModelConfigDTO>>() {},
                "拉取激活模型配置失败, scope=" + scope);
        return unwrap(resp, "拉取激活模型配置失败, scope=" + scope);
    }

    // ---- Token 校验 ----

    /**
     * 校验前端 Bearer Token 有效性。
     *
     * @param token 待校验的 token，不能为 null 或空
     * @return 校验结果；服务端拒绝或响应异常时抛 {@link AuthClientException}
     */
    public TokenVerifyResult verifyToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token 不能为空");
        }
        ApiResponse<TokenVerifyResult> resp = doPost(
                "/api/v1/internal/token/verify",
                new TokenVerifyRequest(token),
                new TypeRef<ApiResponse<TokenVerifyResult>>() {},
                "校验 token 失败");
        return unwrap(resp, "校验 token 失败");
    }

    // ---- 内部工具 ----

    private <T> ApiResponse<T> doGet(String path, TypeRef<ApiResponse<T>> ref, String errPrefix) {
        try {
            return get(path, ref);
        } catch (SdkException e) {
            throw wrapHttpFailure(e, errPrefix);
        }
    }

    private <T> ApiResponse<T> doPost(String path, Object body,
                                      TypeRef<ApiResponse<T>> ref, String errPrefix) {
        try {
            return post(path, body, ref);
        } catch (SdkException e) {
            throw wrapHttpFailure(e, errPrefix);
        }
    }

    /**
     * 将底层 {@link SdkException} 包装为 {@link AuthClientException} 并透传 HTTP 状态码，
     * 避免上层为了拿状态码去遍历异常链。
     */
    private AuthClientException wrapHttpFailure(SdkException e, String errPrefix) {
        int httpStatus = e.getStatusCode() > 0 ? e.getStatusCode() : AuthClientException.UNKNOWN_CODE;
        return new AuthClientException(
                errPrefix + ": " + e.getMessage(),
                httpStatus,
                AuthClientException.UNKNOWN_CODE,
                e);
    }

    private <T> T unwrap(ApiResponse<T> resp, String errPrefix) {
        if (resp == null) {
            throw new AuthClientException(errPrefix + ": 服务端返回空响应体");
        }
        if (!resp.isSuccess()) {
            int bizCode = resp.code() != null ? resp.code() : AuthClientException.UNKNOWN_CODE;
            throw new AuthClientException(
                    errPrefix + ": code=" + resp.code() + " msg=" + resp.msg(),
                    AuthClientException.UNKNOWN_CODE,
                    bizCode,
                    null);
        }
        if (resp.data() == null) {
            throw new AuthClientException(errPrefix + ": 服务端返回 data 为空");
        }
        return resp.data();
    }

    // ===== Builder =====

    public static class Builder {
        private final ClientConfig.Builder configBuilder = ClientConfig.builder();

        public Builder baseUrl(String url) {
            configBuilder.baseUrl(url);
            return this;
        }

        public Builder sharedSecret(String secret) {
            configBuilder.sharedSecret(secret);
            return this;
        }

        public Builder connectTimeout(Duration d) {
            configBuilder.connectTimeout(d);
            return this;
        }

        public Builder readTimeout(Duration d) {
            configBuilder.readTimeout(d);
            return this;
        }

        public Builder maxRetries(int n) {
            configBuilder.maxRetries(n);
            return this;
        }

        public AuthClient build() {
            return new AuthClient(configBuilder.build());
        }
    }
}
