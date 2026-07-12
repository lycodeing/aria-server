## 整改范围：去除非必要环境变量，统一部署配置

### 原则
- DB（username/password/url）、Redis（host/port/password）、RabbitMQ（host/port/username/password）：保留 `${...}` 环境变量
- 其他所有配置：直接写死，本地值放 application.yml，生产值放 application-prod.yml

---

### 文件 1：`ai-auth/auth-service/src/main/resources/application.yml`
- 将 `aria.internal.secret: ${ARIA_INTERNAL_SECRET:aria-internal-lycodeing-2024}` 改为直接写 `aria-internal-lycodeing-2024`

### 文件 2：`ai-knowledge/knowledge-service/src/main/resources/application.yml`
去掉以下 `${...}` 占位符，改为直接本地值：
- `jwt-secret-key` → `cs-auth-dev-secret-key-change-in-production`
- `aria.auth.client.base-url` → `http://localhost:8083`
- `aria.auth.client.shared-secret` → `aria-internal-lycodeing-2024`
- `aria.internal.secret` → `aria-internal-lycodeing-2024`
- `minio.*` → 全部 localhost 默认值直接写死
- `knowledge.search.fts-config` → `simple`
- `knowledge.embedding.batch-size` → `32`
- `knowledge.embedding.timeout-seconds` → `30`
- `knowledge.reranker.enabled` → `false`
- `knowledge.reranker.base-url` → `http://localhost:8001`
- `knowledge.reranker.model-name` → `bge-reranker-v2-m3`
- `ctyun.ai.base-url` → `https://wishub-x6.ctyun.cn/v1`
- `ctyun.ai.api-key` → `""` (空字符串直接写)

### 文件 3：`ai-conversation/conversation-service/src/main/resources/application.yml`
去掉以下 `${...}` 占位符，改为直接本地值：
- `jwt-secret-key` → `cs-auth-dev-secret-key-change-in-production`
- `aria.auth.client.base-url` → `http://localhost:8083`
- `aria.auth.client.shared-secret` → `aria-internal-lycodeing-2024`
- `aria.internal.secret` → `aria-internal-lycodeing-2024`
- `knowledge.client.sharedSecret` → `aria-internal-lycodeing-2024`
- `knowledge.service.base-url` → `http://localhost:8081`（同时修复错误的 8084）
- `knowledge.search.default-kb-id` → `default`
- `app.cors.allowed-origins` → `http://localhost:5173,http://localhost:5670,http://localhost:5671`

### 文件 4（新建）：`ai-auth/auth-service/src/main/resources/application-prod.yml`
```yaml
spring:
  datasource:
    url: jdbc:postgresql://ai-cs-postgres:5432/ai_customerservice
aria:
  internal:
    secret: aria-internal-lycodeing-2024
```

### 文件 5（新建）：`ai-knowledge/knowledge-service/src/main/resources/application-prod.yml`
```yaml
spring:
  datasource:
    url: jdbc:postgresql://ai-cs-postgres:5432/ai_knowledge
sa-token:
  jwt-secret-key: cs-auth-lycodeing-secret-key-2024
aria:
  auth:
    client:
      base-url: http://ai-cs-auth:8083
      shared-secret: aria-internal-lycodeing-2024
  internal:
    secret: aria-internal-lycodeing-2024
minio:
  endpoint: http://ai-cs-minio:9000
  access-key: minioadmin
  secret-key: Lycodeing@2024
  bucket: ai-knowledge
knowledge:
  search:
    fts-config: jieba
ctyun:
  ai:
    api-key: ""
```

### 文件 6（新建）：`ai-conversation/conversation-service/src/main/resources/application-prod.yml`
```yaml
spring:
  datasource:
    url: jdbc:postgresql://ai-cs-postgres:5432/ai_customerservice
sa-token:
  jwt-secret-key: cs-auth-lycodeing-secret-key-2024
aria:
  auth:
    client:
      base-url: http://ai-cs-auth:8083
      shared-secret: aria-internal-lycodeing-2024
  internal:
    secret: aria-internal-lycodeing-2024
knowledge:
  client:
    sharedSecret: aria-internal-lycodeing-2024
  service:
    base-url: http://ai-cs-knowledge:8081
app:
  cors:
    allowed-origins: https://chat.lycodeing.cn
```

### 文件 7：`deploy/docker-compose.yml`
每个 Java 服务 environment 段精简为：
- auth-service：`SPRING_PROFILES_ACTIVE`、`DB_USERNAME`、`DB_PASSWORD`、`REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`
- knowledge-service：同上 + `RABBITMQ_HOST`、`RABBITMQ_PORT`、`RABBITMQ_USERNAME`、`RABBITMQ_PASSWORD`
- conversation-service：同上（与 knowledge 相同的 MQ 变量）

删除所有其他环境变量（SPRING_DATASOURCE_URL、JWT_SECRET_KEY、INTERNAL_SECRET、ARIA_INTERNAL_SECRET、ARIA_AUTH_URL、ARIA_AUTH_INTERNAL_URL、MINIO_*、APP_CORS_ALLOWED_ORIGINS、KNOWLEDGE_SERVICE_BASE_URL、AI_CTYUN_API_KEY）

### 文件 8：`start-local.sh`
- 修正 BACKEND 路径：去掉多余的 `/ai-customerservice` 前缀，改为当前实际项目路径
- 删除 export `INTERNAL_SECRET`（yml 不再读取）
- 删除 export `ARIA_AUTH_INTERNAL_URL`（yml 不再读取）
- 删除 export `AI_CTYUN_API_KEY`（本地无需，yml 已直接写空值）