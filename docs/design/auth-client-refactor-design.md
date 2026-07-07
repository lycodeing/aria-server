# auth-client SDK 抽取与内部调用重构设计

- 版本：v1.1
- 日期：2026-07-07
- 状态：已实现 · 已通过代码评审
- 涉及模块：`ai-common/common-client`、`ai-common/common-web`、`ai-auth/*`、`ai-conversation/*`、`ai-knowledge/*`

**变更历史：**
- v1.0（2026-07-07）：初版草案
- v1.1（2026-07-07）：落地实现 + 代码评审修复；补齐设计遗漏项（见 §11）

---

## 1. 背景

当前 `common-web` 模块内的 `RemoteAiModelConfigProvider` 通过手撸 `WebClient` 链式调用 auth-service 的 `/internal/ai-models/**` 接口，存在以下问题：

1. **模块职责错位**：`common-web` 定位是 Web 层横切基础设施（响应包装、异常处理、Redis 助手），却承载了 auth-service 的 HTTP 协议、URL 路径、鉴权头等业务契约。
2. **契约无归属**：字段名散落在 `JsonNode.path("apiProtocol")` 字符串里，编译期无约束；服务端字段重命名会导致运行时静默降级为默认值。
3. **协议知识分散**：`X-Internal-Secret` 头这种鉴权细节由每个调用点自行拼装，源码注释里 "⚠️ 原版缺失此头导致 403" 就是真实事故的证据。
4. **架构不对称**：`knowledge-service` 有 `knowledge-client` SDK，`auth-service` 却没有，新人首次接触时会疑惑为什么两条路径写法完全不同。
5. **接口在增长**：从 `active` 扩展到 `active-embedding` / `active-router`，还有 `token/verify` 等更多内部端点，SDK 化的边际成本正在快速下降。

## 2. 目标

- 新建 `ai-auth/auth-client` 模块，作为 auth-service 内部接口的唯一入口。
- 在 `common-client` 中扩展"共享密钥"鉴权拦截器，让 `BaseClient` 同时支持 AK/SK 与 `X-Internal-Secret` 两种模式，避免鉴权协议分裂。
- 将 `RemoteAiModelConfigProvider` 中 40 余行手撸 HTTP 代码，替换为 3 行 SDK 调用；保留其缓存与 Pub/Sub 监听职责。
- 消除 `common-web` 对 auth-service 具体接口路径的编译期依赖。
- 为后续 `token/verify`、账户查询、配置变更事件推送等内部接口铺路。

## 3. 非目标

- 不改变 auth-service 侧接口契约（URL、字段、鉴权头保持向后兼容）。
- 不引入服务注册中心或负载均衡组件，`base-url` 仍走配置直连。
- 不重构 `knowledge-client`（现状已符合目标形态，仅作为参考样板）。
- 不处理外网调用鉴权（此次范围仅限内网服务间调用）。

## 4. 现状分析

### 4.1 auth-service 内部接口盘点

| 接口路径 | 方法 | 用途 | 鉴权头 | 调用方 |
|---|---|---|---|---|
| `/internal/ai-models/active` | GET | 拉取当前默认 CHAT 模型（含解密后 apiKey） | `X-Internal-Secret` | conversation-service |
| `/internal/ai-models/active-embedding` | GET | 拉取当前默认 EMBEDDING 模型 | `X-Internal-Secret` | knowledge-service |
| `/internal/ai-models/active-router` | GET | 拉取当前默认 ROUTER 模型 | `X-Internal-Secret` | conversation-service |
| `/api/v1/internal/token/verify` | POST | 校验前端 Bearer Token 有效性 | `X-Internal-Secret` | 所有前置服务 |

响应统一走 `R<T>` 包装：`{ code, msg, data }`；`code == 200` 为业务成功。

### 4.2 现有实现的技术债

- `RemoteAiModelConfigProvider` 位于 `common-web/ai` 包下，混合了三种关注点：
  - **协议翻译**：`WebClient` 拼 URL、加 `X-Internal-Secret` 头、解析 `R<T>` JSON 结构。
  - **缓存治理**：`RedisCacheHelper` 读写 CHAT / EMBEDDING / ROUTER 三份缓存键，TTL 5 分钟。
  - **事件监听**：订阅 `aria:config:ai-changed` Pub/Sub 主题，收到变更后清缓存。
- `JsonNode.path("xxx").asText("default")` 的取值方式将字段容错分散到调用点，服务端字段一旦重命名，客户端不会报错，只会静默拿到默认值。
- `X-Internal-Secret` 的注入方式是 `@Value("${aria.internal.secret:change-this-in-production}")`，默认值是明显的占位符；如果调用方未覆盖，将带着占位符去请求 auth-service 得到 403，报错栈却指向业务代码。
- `common-web` 是每个服务的强制依赖，Web 层横切能力（比如响应包装、异常映射）被绑定到了 auth-service 的接口版本上。auth-service 的接口路径调整，会强制所有服务同步升级 `common-web`。
- `common-client` 已有 `BaseClient` + `AkSkSigningInterceptor` + `RetryInterceptor` 的完整 SDK 骨架，但内部调用未接入，等于同一套代码里跑着两套 HTTP 客户端体系（OkHttp / WebClient）。

### 4.3 参考样板：knowledge-client

`ai-knowledge/knowledge-client` 已是理想形态：

```
knowledge-client/
├── KnowledgeClient.java             // 继承 BaseClient，方法即接口契约
├── KnowledgeClientAutoConfig.java   // Spring Boot 自动装配，读取 knowledge.client.* 配置
└── dto/
    ├── SearchRequest.java
    ├── SearchResponse.java
    └── ChunkHitDTO.java
```

调用方只需在 `application.yml` 声明 `knowledge.client.base-url` / `access-key` / `secret-key`，即可 `@Autowired KnowledgeClient` 直接使用。

### 4.4 阻碍 auth-client 的现实约束

- **鉴权协议不同**：`BaseClient` 现走 AK/SK 签名（HMAC-SHA256 + timestamp + nonce），auth-service 内部接口走静态共享密钥 `X-Internal-Secret`。直接复用 `BaseClient` 会带上无用的 AK/SK 头。
- **潜在循环依赖**：`auth-service` 编译依赖 `common-web`。如果 `common-web` 反向依赖 `auth-client`，图上会形成 `auth-service → common-web → auth-client → (指向 auth-service 的接口契约)`。虽然不是编译期循环（`auth-client` 不依赖 `auth-service` 实现），但仍需在 `auth-service` 内用 `@Primary` 本地实现覆盖，避免自调自己。
- **DTO 归属**：`AiModelConfig` 当前定义在 `common-web/ai` 包，被 auth-service 的 Controller 直接返回。抽离 SDK 时需要决定 DTO 在 `auth-client` 还是 `common-web`。

<!-- SECTION_CURRENT -->

## 5. 方案设计

### 5.1 模块拓扑

```
ai-common/common-client
├── BaseClient                      // 现有
├── ClientConfig                    // 扩展：支持 sharedSecret
├── interceptor/
│   ├── AkSkSigningInterceptor      // 现有
│   ├── SharedSecretInterceptor     // 新增：附加 X-Internal-Secret 头
│   └── RetryInterceptor            // 现有
└── auth/                           // 新增（可选，抽公共认证 SPI）
    └── AuthMode.java               // AK_SK / SHARED_SECRET / NONE

ai-auth/auth-client                 // 新增模块
├── AuthClient.java                 // 门面：extends BaseClient
├── AuthClientAutoConfig.java       // Spring Boot 自动装配
├── model/
│   ├── ModelScope.java             // CHAT / EMBEDDING / ROUTER 枚举
│   └── AiModelConfigDTO.java       // 强类型响应
└── token/
    ├── TokenVerifyRequest.java
    └── TokenVerifyResult.java

ai-common/common-web/ai
└── RemoteAiModelConfigProvider     // 保留：缓存 + Pub/Sub，去掉手撸 HTTP
```

### 5.2 依赖方向

```
auth-service ──► common-web ──► common-client
                     │
                     └────────► auth-client ──► common-client
conversation-service ──► auth-client ──► common-client
knowledge-service    ──► auth-client ──► common-client
```

- `common-web` 通过 `auth-client` 完成 HTTP 调用，接口契约收敛到 SDK。
- `auth-service` 自身**不引入** `auth-client`；提供 `@Primary` 的 `LocalAiModelConfigProvider` 直接查库，规避自调自己。

### 5.3 关键类设计

#### 5.3.1 `SharedSecretInterceptor`

```java
public class SharedSecretInterceptor implements Interceptor {
    private static final String HEADER = "X-Internal-Secret";
    private final String secret;

    public SharedSecretInterceptor(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("内部共享密钥不能为空");
        }
        this.secret = secret;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request req = chain.request().newBuilder()
                .header(HEADER, secret)
                .build();
        return chain.proceed(req);
    }
}
```

- 启动时校验密钥非空，避免"占位符默认值 → 运行时 403"链路。
- 密钥值不写入日志。

#### 5.3.2 `ClientConfig` 扩展

```java
public class ClientConfig {
    private String baseUrl;
    private AuthMode authMode = AuthMode.AK_SK;
    private String accessKey;
    private String secretKey;
    private String sharedSecret;        // 新增
    // 超时、重试...
}
```

`BaseClient` 构造时按 `authMode` 二选一装配拦截器：

```java
protected BaseClient(ClientConfig config) {
    OkHttpClient.Builder b = new OkHttpClient.Builder()
            .connectTimeout(config.getConnectTimeout())
            .readTimeout(config.getReadTimeout());
    switch (config.getAuthMode()) {
        case AK_SK          -> b.addInterceptor(new AkSkSigningInterceptor(config));
        case SHARED_SECRET  -> b.addInterceptor(new SharedSecretInterceptor(config.getSharedSecret()));
        case NONE           -> { /* 无鉴权 */ }
    }
    b.addInterceptor(new RetryInterceptor(config.getMaxRetries()));
    this.httpClient = b.build();
}
```

#### 5.3.3 `AuthClient`

```java
public class AuthClient extends BaseClient {

    private AuthClient(ClientConfig config) { super(config); }

    public static Builder builder() { return new Builder(); }

    /** 拉取当前激活的 AI 模型配置（按 scope 分流）。 */
    public AiModelConfigDTO getActiveModel(ModelScope scope) {
        String path = switch (scope) {
            case CHAT      -> "/internal/ai-models/active";
            case EMBEDDING -> "/internal/ai-models/active-embedding";
            case ROUTER    -> "/internal/ai-models/active-router";
        };
        R<AiModelConfigDTO> resp = get(path, new TypeRef<R<AiModelConfigDTO>>() {});
        return unwrap(resp, "获取激活模型配置失败, scope=" + scope);
    }

    /** 校验前端 Bearer Token。 */
    public TokenVerifyResult verifyToken(String token) {
        R<TokenVerifyResult> resp = post(
                "/api/v1/internal/token/verify",
                new TokenVerifyRequest(token),
                new TypeRef<R<TokenVerifyResult>>() {});
        return unwrap(resp, "校验 token 失败");
    }

    private <T> T unwrap(R<T> r, String errMsg) {
        if (r == null) throw new AuthClientException(errMsg + ": 空响应");
        if (r.getCode() != 200) throw new AuthClientException(
                errMsg + ": code=" + r.getCode() + " msg=" + r.getMsg());
        return r.getData();
    }

    public static class Builder { /* baseUrl / sharedSecret / timeouts / build() */ }
}
```

- 返回强类型 `AiModelConfigDTO`（`record`），字段一旦缺失或改名，Jackson 反序列化会直接报错，避免"静默默认值"陷阱。
- `TypeRef<R<T>>` 处理泛型擦除。若 `BaseClient` 目前只支持 `Class<T>`，需扩展重载（成本约 10 行）。
- 业务错误统一封装为 `AuthClientException`（继承 `SdkException`），上层可基于类型做熔断。

#### 5.3.4 `AuthClientAutoConfig`

```yaml
# application.yml
aria:
  auth:
    client:
      base-url: ${ARIA_AUTH_URL:http://localhost:8083}
      shared-secret: ${ARIA_INTERNAL_SECRET}      # 必填，无默认值
      connect-timeout-ms: 3000
      read-timeout-ms: 5000
      max-retries: 2
```

```java
@AutoConfiguration
@ConditionalOnClass(AuthClient.class)
public class AuthClientAutoConfig {

    @Bean
    @ConditionalOnMissingBean
    public AuthClient authClient(AuthClientProperties props) {
        return AuthClient.builder()
                .baseUrl(props.getBaseUrl())
                .sharedSecret(props.getSharedSecret())
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .readTimeout(Duration.ofMillis(props.getReadTimeoutMs()))
                .maxRetries(props.getMaxRetries())
                .build();
    }
}
```

- 通过 `spring.factories` / `AutoConfiguration.imports` 暴露。
- `shared-secret` 无默认值；未配置直接启动失败，消除"默认占位符"隐患。

#### 5.3.5 `RemoteAiModelConfigProvider` 瘦身后形态

```java
@Slf4j
@AutoConfiguration
public class RemoteAiModelConfigProvider implements AiModelConfigProvider, MessageListener {
    // ... 保留缓存键、TTL、Pub/Sub 主题常量 ...

    private final RedisCacheHelper cache;
    private final AuthClient       authClient;

    public RemoteAiModelConfigProvider(RedisCacheHelper cache,
                                       AuthClient authClient,
                                       ObjectProvider<RedisMessageListenerContainer> container) {
        this.cache      = cache;
        this.authClient = authClient;
        container.ifAvailable(c -> c.addMessageListener(this, new ChannelTopic(PUBSUB_TOPIC)));
    }

    @Override
    public AiModelConfig getActive() {
        return cache.getOrLoad(CHAT_CACHE_KEY, AiModelConfig.class, CACHE_TTL,
                () -> toAiModelConfig(authClient.getActiveModel(ModelScope.CHAT)));
    }
    // getActiveEmbedding / getActiveRouter 同构
}
```

- 手撸 HTTP 的 40 余行代码被 SDK 单行调用替换。
- `AiModelConfig` 与 `AiModelConfigDTO` 之间做一次显式映射，两侧字段可各自演进；避免 SDK 层依赖 `common-web` 的领域类型。

### 5.4 DTO 归属决策

`AiModelConfigDTO` 定义在 `auth-client/model` 下，字段与 auth-service 接口响应一一对应；`AiModelConfig`（面向业务的 record）保留在 `common-web/ai`，作为 `AiModelConfigProvider` SPI 的返回类型。两者通过 `RemoteAiModelConfigProvider` 内的转换方法解耦。

<!-- SECTION_DESIGN -->

## 6. 迁移步骤

按依赖顺序分五个阶段推进，每一步可独立通过本地验证，允许在任意阶段中止不留半成品。

### 阶段 1：`common-client` 增强（无破坏性）

1. 新增 `com.aria.common.sdk.auth.AuthMode` 枚举。
2. 在 `ClientConfig` 增加 `authMode` / `sharedSecret` 字段，默认值保持 `AK_SK`，老调用方零感知。
3. 新增 `SharedSecretInterceptor`；`BaseClient` 构造逻辑改为按 `authMode` 分派。
4. `BaseClient` 增加 `get(String, TypeRef<T>)` / `post(String, Object, TypeRef<T>)` 重载，支持泛型响应类型。
5. 单元测试：`SharedSecretInterceptor` 请求头存在性、密钥为空时抛异常、`AK_SK` 模式旧行为不变。

**产物**：`common-client` 打出快照版本，其他模块暂不升级。

### 阶段 2：`auth-client` 模块落地

1. 在 `ai-auth/` 下新建 Maven 子模块 `auth-client`，`pom.xml` 只依赖 `common-client`。
2. 创建包结构：
   - `com.aria.sdk.auth.AuthClient`
   - `com.aria.sdk.auth.AuthClientAutoConfig`
   - `com.aria.sdk.auth.AuthClientProperties`
   - `com.aria.sdk.auth.exception.AuthClientException`
   - `com.aria.sdk.auth.model.{ModelScope, AiModelConfigDTO}`
   - `com.aria.sdk.auth.token.{TokenVerifyRequest, TokenVerifyResult}`
3. 在 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 中登记 `AuthClientAutoConfig`。
4. 编写 SDK 单元测试（MockWebServer）：
   - `getActiveModel(CHAT/EMBEDDING/ROUTER)` URL 正确、头正确、响应解析正确。
   - `verifyToken` 成功与失败路径。
   - 服务端 404 / 500 / code≠200 均映射为 `AuthClientException`。
5. 在 `ai-auth/pom.xml` 注册子模块；根 `pom.xml` `<dependencyManagement>` 声明 `auth-client` 版本。

**产物**：`auth-client` 可独立编译测试通过。

### 阶段 3：接入 `common-web`

1. `common-web/pom.xml` 增加 `auth-client` 依赖。
2. 重写 `RemoteAiModelConfigProvider`：
   - 删除 `WebClient` / `ObjectMapper` / `authInternalUrl` / `internalSecret` 字段。
   - 构造器改为注入 `AuthClient`。
   - 三个 `getActiveXxx` 方法内部调用 `authClient.getActiveModel(scope)` 并做 DTO → `AiModelConfig` 映射。
3. `AiModelConfigProvider` SPI 保持签名不变（返回 `AiModelConfig`），业务侧零感知。
4. 单元测试：Mock `AuthClient`，验证缓存命中/未命中路径、Pub/Sub 失效回调路径。

**产物**：`common-web` 完成瘦身，本地跑 `mvn -pl ai-common/common-web -am test` 全绿。

### 阶段 4：`auth-service` 本地实现

1. `auth-service` **不引入** `auth-client`。
2. 新增 `LocalAiModelConfigProvider`：直接调用 `AiModelConfigService` 查库，标注 `@Primary` 覆盖 `RemoteAiModelConfigProvider`。
3. 若 `auth-service` 内部调用自身接口的场景为零，可直接不装配 `RemoteAiModelConfigProvider`：用 `@ConditionalOnProperty(name="aria.auth.internal-url")` 控制注册；`auth-service` 不设置该属性即可。
4. 冒烟测试：auth-service 启动，`/actuator/health` 正常，`/internal/ai-models/active` 返回 200。

### 阶段 5：调用方切换与旧代码清理

1. `conversation-service` / `knowledge-service` 的 `application.yml` 由：
   ```yaml
   aria:
     auth:
       internal-url: ${ARIA_AUTH_URL}
     internal:
       secret: ${ARIA_INTERNAL_SECRET}
   ```
   迁移为：
   ```yaml
   aria:
     auth:
       client:
         base-url: ${ARIA_AUTH_URL}
         shared-secret: ${ARIA_INTERNAL_SECRET}
   ```
2. 部署脚本 `deploy/` 与 `start-local.sh` 同步更新环境变量注入。
3. 删除 `RemoteAiModelConfigProvider` 里的旧字段与 `fetchFromAuthService` 方法。
4. 全量回归：三个服务本地启停 + `/actuator/health` + 一轮聊天冒烟。
5. 更新 `AGENTS.md` 与本设计文档的"必需环境变量"表。

### 阶段 6：`token/verify` 迁移（可选、独立）

- 各服务原本手撸的 token 校验 HTTP 调用替换为 `authClient.verifyToken(token)`。
- 该阶段与前五步解耦，可独立排期。

<!-- SECTION_MIGRATION -->

## 7. 影响面与兼容性

### 7.1 模块变更矩阵

| 模块 | 变更类型 | 说明 |
|---|---|---|
| `ai-common/common-client` | 增量 | 新增 `AuthMode` / `SharedSecretInterceptor` / `TypeRef` 重载，旧 API 保持不变 |
| `ai-auth/auth-client` | 新增 | 全新 SDK 模块 |
| `ai-common/common-web` | 破坏性重构 | `RemoteAiModelConfigProvider` 内部实现重写；SPI 契约不变 |
| `ai-auth/auth-service` | 增量 | 新增 `LocalAiModelConfigProvider`，覆盖远端实现 |
| `ai-conversation/conversation-service` | 配置迁移 | `application.yml` 配置键调整 |
| `ai-knowledge/knowledge-service` | 配置迁移 | 同上 |
| `deploy/`、`start-local.sh` | 配置迁移 | 环境变量键调整 |

### 7.2 接口契约兼容性

- auth-service 对外暴露的 HTTP 接口（URL、方法、请求头、响应体）**保持不变**。
- SDK 内部与服务端的字段映射为强类型，若服务端未来新增字段，客户端默认忽略未知字段（Jackson `FAIL_ON_UNKNOWN_PROPERTIES=false`）；如果服务端要删除字段，需与所有 `auth-client` 消费者协同升级。

### 7.3 配置键迁移对照

| 旧键 | 新键 | 备注 |
|---|---|---|
| `aria.auth.internal-url` | `aria.auth.client.base-url` | 语义统一到 SDK 命名空间 |
| `aria.internal.secret` | `aria.auth.client.shared-secret` | 同上，且不再提供占位默认值 |
| （无） | `aria.auth.client.connect-timeout-ms` | 默认 3000 |
| （无） | `aria.auth.client.read-timeout-ms` | 默认 5000 |
| （无） | `aria.auth.client.max-retries` | 默认 2 |

- 环境变量 `ARIA_AUTH_URL` / `ARIA_INTERNAL_SECRET` 可继续复用，通过占位符注入。
- 应用启动时校验 `shared-secret` 非空，缺失即启动失败（fail-fast）。

### 7.4 运行时行为差异

- **HTTP 客户端切换**：`WebClient`（Reactor Netty）→ `OkHttp`。响应式链路上如需 `Mono<AiModelConfig>`，需要在 `RemoteAiModelConfigProvider` 外层包一次 `Mono.fromCallable(...)`。目前 `AiModelConfigProvider` 是同步 SPI，改动可控。
- **重试策略**：`RetryInterceptor` 默认指数退避 + 幂等方法重试（GET 幂等，POST 不自动重试）。这与原 `WebClient` 未启用重试的行为不同，需评估是否会对 auth-service 造成读放大；`max-retries=2` 是保守值。
- **超时**：`connectTimeout=3s` / `readTimeout=5s`，与原实现的 `timeout(Duration.ofSeconds(5))` 语义一致但更明确。
- **错误映射**：所有非 200 响应或反序列化失败均抛 `AuthClientException`；`RemoteAiModelConfigProvider` 上层继续用 `IllegalStateException` 兜底以保持既有降级链。

### 7.5 部署与灰度

- SDK 是纯客户端变更，无需服务端灰度。
- 建议按 `auth-service → common-web 升级 → conversation-service / knowledge-service 升级` 的顺序滚动发布，任一环节失败可仅回滚该服务。
- 生产环境需先在预发验证 `X-Internal-Secret` 已通过新配置键注入，避免上线时静默 403。

<!-- SECTION_IMPACT -->

## 8. 风险与回滚

### 8.1 风险清单

| 编号 | 风险描述 | 触发场景 | 缓解措施 | 严重度 |
|---|---|---|---|---|
| R1 | 循环依赖导致 auth-service 无法启动 | `common-web` → `auth-client`，且 auth-service 装配了 `RemoteAiModelConfigProvider` | 在 auth-service 显式声明 `@Primary` `LocalAiModelConfigProvider`，或用 `@ConditionalOnProperty` 关闭远端实现 | 高 |
| R2 | 配置键迁移遗漏，生产环境 403 | 部署脚本未同步更新 `aria.auth.client.shared-secret` | 阶段 5 上线前在预发全量对齐环境变量；SDK 启动时对空密钥 fail-fast | 高 |
| R3 | Jackson 反序列化因字段类型差异失败 | auth-service 返回字段为 `Integer` 但 DTO 为 `int` 且 null | DTO 全部使用装箱类型或 `Optional`；开启 `FAIL_ON_UNKNOWN_PROPERTIES=false` | 中 |
| R4 | OkHttp 与 `WebClient` 行为差异（如 gzip、代理设置） | 生产环境有 HTTP 代理或压缩策略 | 保留超时值与原实现一致；灰度环境跑一轮端到端 | 中 |
| R5 | RetryInterceptor 对幂等 GET 触发重试放大 | auth-service 短暂抖动 | `max-retries=2` 保守设置；观察 auth-service QPS 指标 | 低 |
| R6 | Pub/Sub 监听在改造中被误删 | 重写 `RemoteAiModelConfigProvider` 时遗漏容器注册 | 单元测试覆盖 `onMessage` 回调；code review 关注点 | 中 |
| R7 | `TypeRef` 泛型解析在 GraalVM Native 场景失效 | 未来引入 Native Image | 现无 Native 构建计划；出现时再补 reflect-config | 低 |
| R8 | SDK 版本与服务端接口不一致 | auth-service 独立发版调整字段 | `auth-client` 与 auth-service 同仓、版本同步；合入 PR 必须双向修改 | 中 |

### 8.2 回滚策略

按阶段分别设计回滚方案，任一阶段独立可回滚。

- **阶段 1（`common-client` 增强）**：纯增量代码，回滚等同于 revert commit。已构建但未消费的模块无破坏。
- **阶段 2（`auth-client` 新增）**：模块未被引用即为死代码，直接 revert 即可。
- **阶段 3（`common-web` 重构）**：保留旧 `RemoteAiModelConfigProvider` 一份副本，命名为 `LegacyRemoteAiModelConfigProvider`，通过 `@ConditionalOnProperty(name="aria.auth.client.enabled", havingValue="true", matchIfMissing=true)` 控制启用。回滚只需将属性设为 `false` 并重启，无需回码。
- **阶段 4（auth-service 本地实现）**：`LocalAiModelConfigProvider` 是新增 bean，回滚等于删 bean，无副作用。
- **阶段 5（配置切换）**：`application.yml` 保留旧键读取分支 2 个发布周期（`@Value("${aria.auth.client.base-url:${aria.auth.internal-url:}}")`），任一部署环境未及时更新配置也不会中断。
- **阶段 6（`token/verify` 迁移）**：SDK 调用与直连 HTTP 调用可共存；回滚即恢复直连代码。

### 8.3 数据一致性

本次改造不涉及数据结构变更，无数据库迁移与数据回滚风险。Redis 缓存键保持不变（`aria:ai:model:*`），Pub/Sub 主题保持不变（`aria:config:ai-changed`），已缓存数据不会失效。

<!-- SECTION_RISK -->

## 9. 验证方案

所有验证均在本地 AI 自动执行，禁止依赖 CI 或远程流水线。

### 9.1 单元测试矩阵

| 模块 | 测试类 | 覆盖点 |
|---|---|---|
| `common-client` | `SharedSecretInterceptorTest` | 请求头附加、空密钥抛异常、密钥不写入日志 |
| `common-client` | `BaseClientAuthModeTest` | `AK_SK` / `SHARED_SECRET` / `NONE` 三种模式的拦截器装配 |
| `auth-client` | `AuthClientTest`（MockWebServer） | 三个 `ModelScope` 的 URL 与 Header、成功/404/500/code≠200 |
| `auth-client` | `AuthClientPropertiesTest` | 缺失 `shared-secret` 时启动失败 |
| `common-web` | `RemoteAiModelConfigProviderTest` | 缓存命中不发起 HTTP、缓存未命中调用 `AuthClient`、Pub/Sub 触发缓存清理、DTO→Config 映射正确性 |
| `auth-service` | `LocalAiModelConfigProviderTest` | 直查库路径、`@Primary` 覆盖生效 |

### 9.2 集成测试

启动 auth-service + Redis + PostgreSQL，模拟 conversation-service 侧调用：

1. 未配置 `shared-secret` 时，conversation-service 启动失败，日志包含 "内部共享密钥不能为空"。
2. 配置正确密钥后，`AiModelConfigProvider.getActive()` 首次调用触发 HTTP，返回结果落缓存。
3. 第二次调用不触发 HTTP（通过 auth-service 访问日志确认）。
4. 后台修改默认模型 → 推送 `aria:config:ai-changed` → conversation-service 下次调用触发 HTTP 并拿到新配置。
5. 停掉 auth-service，触发降级逻辑，返回兜底配置或抛出可捕获异常。
6. 恢复 auth-service，缓存 TTL 过期后自动恢复。

### 9.3 冒烟场景

- 全量启动 auth-service (8083) / conversation-service (8082) / knowledge-service (8081)。
- 前端发起一次完整聊天：token 校验通过、CHAT 模型正确加载、EMBEDDING 检索有结果、ROUTER 路由正确。
- 手动 `curl -H "X-Internal-Secret: xxx" http://localhost:8083/internal/ai-models/active` 验证服务端契约未变。

### 9.4 本地构建命令

```bash
MVN=/Users/lycodeing/apache-maven-3.9.12/bin/mvn

# 阶段 1 验证
$MVN -pl ai-common/common-client -am clean test

# 阶段 2 验证
$MVN -pl ai-auth/auth-client -am clean test

# 阶段 3 验证
$MVN -pl ai-common/common-web -am clean test

# 阶段 4 验证
$MVN -pl ai-auth/auth-service -am clean test

# 全量回归
$MVN clean package
```

### 9.5 验收清单

- [ ] `RemoteAiModelConfigProvider` 中不再出现 `WebClient` / `X-Internal-Secret` 字面量。
- [ ] `auth-client` 单元测试全部通过，覆盖率 ≥ 80%。
- [ ] `common-web` 与 `auth-service` 编译产物不产生循环依赖（`mvn dependency:tree` 无警告）。
- [ ] 三个服务在本地全量启动并冒烟通过。
- [ ] 环境变量文档（`AGENTS.md`）已同步更新，旧变量保留至少一个发布周期。
- [ ] 部署脚本 `deploy/` 与 `start-local.sh` 已同步。

<!-- SECTION_VERIFY -->

## 10. 未来演进

以下方向不属于本次改造范围，但设计需为其保留扩展点。

### 10.1 内部调用鉴权升级

`X-Internal-Secret` 是静态共享密钥，一旦泄露需全环境滚动。演进路径：

- **短期**：在 `SharedSecretInterceptor` 之上叠加 `X-Request-Id` / 时间戳，服务端启用防重放窗口。
- **中期**：切换为 mTLS，客户端证书由内部 CA 签发，`AuthMode` 增加 `MTLS`；`ClientConfig` 允许注入 `SSLContext`。
- **长期**：接入服务网格（Istio / Linkerd），鉴权下沉到 Sidecar，`auth-client` 只保留业务契约。

### 10.2 配置推送模型

当前依赖 `aria:config:ai-changed` Pub/Sub 主动清缓存 + 5 分钟 TTL 兜底，存在 5 分钟的一致性窗口。

- **服务端推送**：`auth-client` 订阅 SSE / WebSocket 长连接，服务端配置变更时主动推送最新值；客户端本地缓存直接更新而非失效。
- **强一致读**：新增 `authClient.getActiveModel(scope, ConsistencyMode.STRONG)`，绕过缓存直查；供后台管理场景使用。

### 10.3 SDK 复用扩展

- `auth-client` 稳定后，将 `knowledge-client` 与 `auth-client` 的 `AutoConfig` 抽取公共父类，减少重复。
- 若未来落地 `pipeline-client` / `agent-client` 等，可通过在 `common-client` 引入 `ClientRegistry` 统一管理生命周期与指标上报。

### 10.4 可观测性

- SDK 层接入 Micrometer：`auth.client.request.duration` / `auth.client.request.errors`，按 `scope` 与 HTTP status 打标签。
- 支持 W3C Trace Context 透传，OkHttp 拦截器注入 `traceparent`，与 auth-service 侧链路串联。
- 关键错误（连续 N 次 5xx）通过 `MeterRegistry` 触发告警而非依赖日志抓取。

### 10.5 SDK 版本策略

- `auth-client` 与 auth-service 同仓、版本同步，主版本号变化即代表接口不兼容。
- 提供 `X-Auth-Api-Version` 请求头，服务端可基于此在过渡期同时支持新旧字段，帮助跨服务灰度。
- 引入 `contract-tests`：`auth-client` 内附一份服务端契约快照，服务端合入 PR 时自动校验向后兼容性。

### 10.6 独立可能性评估

后续若 `auth-client` 需要供外部生态使用：

- 剥离对 `common-client`（内部 SDK 骨架）的耦合，改用 `Feign` / `spring-cloud-openfeign` 等标准生态。
- 独立 GAV 坐标发布，`auth-service` 与 `auth-client` 依赖同一份 `api` 模块（仅 DTO 与常量），实现真正的 API-first。

<!-- SECTION_FUTURE -->

## 11. 实现补丁 — 代码评审修复（v1.1）

初版实现完成后经过一轮完整代码评审（DDD + 阿里巴巴 Java 开发手册维度），本节记录相对 v1.0 设计的**新增决策**与**修复项**。所有修复均通过 `mvn clean test` + `mvn clean package -DskipTests` 全量回归。

### 11.1 服务端 fail-fast（Critical）

设计 v1.0 仅承诺 SDK 侧对 `shared-secret` fail-fast；实际部署时 auth-service 服务端 `${INTERNAL_SECRET:change-this-in-production}` 仍带占位默认值，两侧配置错配时服务端会静默启动。

**修复**：
- 新增 `auth-service` 侧 `InternalSecretVerifier`（`infrastructure/security/internal`），`@PostConstruct` 校验密钥非空。
- `auth-service/application.yml` 的 `aria.internal.secret` 改为 `${ARIA_INTERNAL_SECRET:${INTERNAL_SECRET:}}`，无占位默认值。
- 两个 `Internal*Controller` 抽掉重复的 `if (secret == null || !equals)` 逻辑，改为调用 `secretVerifier.matches(secret)`。

### 11.2 `AuthClientException` 透传 HTTP 状态与业务码（Critical）

设计 v1.0 用 `throw new AuthClientException(msg, cause)` 一次性包装，丢失了原始 HTTP 状态码。上层做熔断策略（401/403 触发密钥重取，5xx 触发缓存兜底）时被迫做异常链遍历。

**修复**：
- `AuthClientException` 新增 `int httpStatus` 与 `int bizCode` 字段，占位常量 `UNKNOWN_CODE = -1`。
- `AuthClient` 内新增 `wrapHttpFailure(SdkException, errPrefix)` 提取 HTTP 状态；`unwrap()` 中的业务码分支提取 `resp.code()`。
- 测试新增 `assertThat(ex.getHttpStatus()).isEqualTo(500)` / `assertThat(ex.getBizCode()).isEqualTo(404)` 明确断言。

### 11.3 DDD 分层：`LocalAiModelConfigProvider` 归位（Important）

设计 v1.0 将 `LocalAiModelConfigProvider` 放置于 `auth-service/infrastructure/ai`，但它依赖 `application.service.AiModelConfigService`。基础设施反向依赖应用层违反 DDD 铁律。

**修复**：
- 迁移至 `auth-service/application/ai/LocalAiModelConfigProvider.java`。
- 依赖方向调整为 `application → application`，符合"应用层可依赖应用层"的规则。
- 类 Javadoc 明确标注"实现应用层 SPI `AiModelConfigProvider`，本地直查 DB 兜底"。

### 11.4 `AiModelConfig` 包装类型（Important）

设计 v1.0 中 `AiModelConfig` 三个数值字段为原始类型（`double / int / int`），违反阿里【强制】"POJO 属性必须使用包装类型"。

**修复**：改为 `Double / Integer / Integer`，与 `AiModelConfigDTO` 保持一致。Provider 层保证非空（缺失从 `AiModelScopeDefaults` 兜底），下游 `cfg.temperature()` 自动拆箱安全。

### 11.5 默认值统一收敛到 `AiModelScopeDefaults`（Important）

设计 v1.0 三套 `(temperature, maxTokens, timeoutSec)` 默认值以字面量分散在 `RemoteAiModelConfigProvider` / `LocalAiModelConfigProvider` / `InternalAiModelController` 三处，违反【强制】"不允许魔法值"。

**修复**：新增 `common-web/ai/AiModelScopeDefaults` 枚举：

```java
public enum AiModelScopeDefaults {
    CHAT(0.7D, 2048, 60),
    EMBEDDING(0.0D, 0, 30),
    ROUTER(0.0D, 32, 5);
    // ...
}
```

三个消费点全部改为 `AiModelScopeDefaults.CHAT.defaultTemperature()` 读取。`InternalAiModelController` 端保留 `private static final` 常量（避免服务端反向依赖 SDK 侧枚举），但常量值与枚举保持一致，未来考虑抽取 `common-ai-domain` 模块统一。

### 11.6 `AuthClientProperties` `@Validated` + 包装类型（Important）

设计 v1.0 依靠 `SharedSecretInterceptor` 构造异常做 fail-fast，错误栈晦涩。

**修复**：
- 新增 `spring-boot-starter-validation` 依赖。
- 属性类加 `@Validated` + `@NotBlank shared-secret` + `@Min(100) connectTimeoutMs / readTimeoutMs` + `@Min(0) maxRetries`。
- 所有 `int` 字段改为 `Integer`。
- 缺失 `shared-secret` 时 Spring 抛 `BindValidationException`，错误信息直接指向字段。

### 11.7 `maxRetries` 默认改为 0（Important）

设计 v1.0 `maxRetries=2` 与 `RetryInterceptor` 的指数退避（5s / 15s）组合，最坏情况阻塞 30s。内部同步 SPI 调用无法接受。

**修复**：默认改为 0，`AuthClientProperties.maxRetries` Javadoc 明确说明"内部调用有 5 分钟 Redis 缓存兜底，短暂 5xx 由缓存吸收即可，重试放大延迟得不偿失"。

### 11.8 测试方法名英文化 + `@DisplayName`（Important）

阿里【强制】"命名严禁中文"。

**修复**：`SharedSecretInterceptorTest` / `ClientConfigTest` / `BaseClientAuthModeTest` / `AuthClientTest` / `RemoteAiModelConfigProviderTest` 全部方法名改英文，中文描述通过 `@DisplayName` 承载。

### 11.9 `AuthClientAutoConfigTest` 集成测试（Important）

设计 v1.0 §9.1 遗漏。

**修复**：新增 `AuthClientAutoConfigTest`，用 `ApplicationContextRunner` 覆盖三条路径：
1. 正常配置 → 装配 `AuthClient` Bean
2. `enabled=false` → 不装配
3. 缺 `shared-secret` → `BindValidationException` 启动失败

### 11.10 敏感字段屏蔽（Minor）

`AiModelConfigDTO` 手写 `toString()` 屏蔽 `apiKey='***'`，防止调用方误将 DTO 直接日志打印导致密钥泄漏。

### 11.11 全部新增/修改类补齐 `@author` + `@since`

阿里【强制】"所有类必须添加创建者与创建日期"。

涉及类：`AuthMode` / `SharedSecretInterceptor` / `AuthClient` / `AuthClientAutoConfig` / `AuthClientProperties` / `AuthClientException` / `ModelScope` / `AiModelConfigDTO` / `TokenVerifyRequest` / `TokenVerifyResult` / `ApiResponse` / `LocalAiModelConfigProvider` / `AiModelScopeDefaults` / `AiModelConfig` / `InternalSecretVerifier` / 全部单元测试类。

### 11.12 未落地的评审建议（有意保留）

- **`ApiResponse<T>` vs 设计中的 `R<T>`**：刻意保留 SDK 端独立命名，避免 SDK 反向依赖 `common-web`；已在 §5.3.3 补充说明。
- **`@author` 标签统一为 `lycodeing`**：项目单人维护，未来多人协作时补 code owner。
- **`InternalAiModelController` 服务端常量未抽取到公共模块**：跨模块常量共享需要新建 `common-ai-domain` 模块，超出本次改造范围，登记为技术债。

### 11.13 最终验证结果

```
CS Common Client   → 13/13 tests SUCCESS
AI Auth Client SDK → 12/12 tests SUCCESS（含 3 个 AutoConfigTest）
CS Common Web      →  8/8  tests SUCCESS
全部 mvn test       → SUCCESS
mvn clean package  → SUCCESS
```

