package com.aria.sdk.auth;

import com.aria.common.sdk.BaseClient;
import com.aria.common.sdk.ClientConfig;
import com.aria.common.sdk.TypeRef;
import com.aria.common.sdk.auth.AuthMode;
import com.aria.common.sdk.exception.SdkException;
import com.aria.sdk.auth.exception.AuthClientException;
import com.aria.sdk.auth.internal.ApiResponse;
import com.aria.sdk.auth.model.AiModelConfigDTO;
import com.aria.sdk.auth.model.ModelScope;
import com.aria.sdk.auth.token.TokenVerifyRequest;
import com.aria.sdk.auth.token.TokenVerifyResult;
import lombok.extern.slf4j.Slf4j;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
@Slf4j
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
                new TypeRef<>() {
                },
                "拉取激活模型配置失败, scope=" + scope);
        return unwrap(resp, "拉取激活模型配置失败, scope=" + scope);
    }

    // ---- 系统配置 ----

    /**
     * 读取单个系统配置值（启用且未删除）。
     * 调用 auth-service GET /internal/system-config/value?key={configKey}
     *
     * @param configKey 配置键，如 "routing.config"
     * @return 配置值字符串；key 不存在、已禁用或服务异常时返回 null
     */
    public String getSystemConfigValue(String configKey) {
        if (configKey == null || configKey.isBlank()) {
            throw new IllegalArgumentException("configKey 不能为空");
        }
        try {
            String encodedKey = URLEncoder.encode(configKey, StandardCharsets.UTF_8);
            ApiResponse<String> resp = doGet(
                    "/internal/system-config/value?key=" + encodedKey,
                    new TypeRef<ApiResponse<String>>() {},
                    "读取系统配置失败 key=" + configKey);
            return resp != null && resp.isSuccess() ? resp.data() : null;
        } catch (AuthClientException e) {
            log.warn("[AuthClient] 读取系统配置失败 key={}: {}", configKey, e.getMessage());
            return null;
        }
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

    // ---- 用户查询 ----

    /**
     * 分页搜索用户（内部接口，供会话查询页面搜索客服列表）。
     * 调用 auth-service GET /api/v1/internal/users/search
     *
     * @param keyword 搜索关键词（可选，模糊匹配 username/displayName/email）
     * @param page    页码（0-based）
     * @param size    每页大小
     * @return 用户列表分页结果；服务异常时返回空结果
     */
    public java.util.Map<String, Object> searchUsers(String keyword, int page, int size) {
        try {
            StringBuilder path = new StringBuilder("/api/v1/internal/users/search?page=")
                    .append(page).append("&size=").append(size);
            if (keyword != null && !keyword.isBlank()) {
                path.append("&keyword=").append(URLEncoder.encode(keyword, StandardCharsets.UTF_8));
            }
            ApiResponse<java.util.Map<String, Object>> resp = doGet(
                    path.toString(),
                    new TypeRef<>() {},
                    "搜索用户失败 keyword=" + keyword);
            return unwrap(resp, "搜索用户失败 keyword=" + keyword);
        } catch (AuthClientException e) {
            log.warn("[AuthClient] 搜索用户失败 keyword={}: {}", keyword, e.getMessage());
            return java.util.Map.of("total", 0, "page", page, "size", size, "items", java.util.List.of());
        }
    }

    /**
     * 批量查询用户显示名称。
     * 调用 auth-service GET /api/v1/internal/users/names?ids=1,2,3
     *
     * @param ids 用户 ID 字符串列表
     * @return id → displayName 映射；服务异常时返回空 Map
     */
    public java.util.Map<String, String> getDisplayNames(java.util.List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return java.util.Map.of();
        }
        try {
            String idsParam = URLEncoder.encode(String.join(",", ids), StandardCharsets.UTF_8);
            ApiResponse<java.util.Map<String, String>> resp = doGet(
                    "/api/v1/internal/users/names?ids=" + idsParam,
                    new TypeRef<>() {},
                    "批量查询用户名称失败");
            return resp != null && resp.isSuccess() && resp.data() != null
                    ? resp.data()
                    : java.util.Map.of();
        } catch (AuthClientException e) {
            log.warn("[AuthClient] 批量查询用户名称失败: {}", e.getMessage());
            return java.util.Map.of();
        }
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
            return new AuthClient(configBuilder.authMode(AuthMode.SHARED_SECRET).build());
        }
    }
}
