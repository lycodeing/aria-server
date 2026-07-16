<div align="center">

<img src="https://img.shields.io/badge/ARIA-AI%20Customer%20Service-6366f1?style=for-the-badge&logo=robot&logoColor=white" alt="ARIA"/>

# ARIA — AI Realtime Intelligent Agent

**企业级 AI 智能客服平台后端**

[![Java](https://img.shields.io/badge/Java-17-orange?logo=openjdk)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-6db33f?logo=springboot)](https://spring.io/projects/spring-boot)
[![LangChain4j](https://img.shields.io/badge/LangChain4j-1.1.0-blueviolet)](https://github.com/langchain4j/langchain4j)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16%20+%20pgvector-336791?logo=postgresql)](https://github.com/pgvector/pgvector)
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

[English](#english) · [中文](#中文) · [快速开始](#-快速开始) · [架构图](#-系统架构) · [API 文档](#-api-概览)

</div>

---

## 简介

ARIA 是一套面向企业的 **AI 驱动智能客服系统**，融合了大语言模型（LLM）对话、RAG 知识库检索、人机协作接管与结构化意图路由，帮助企业以最低运营成本提供 7×24 小时高质量客户服务。

**核心能力一览：**

- 🤖 **AI 对话引擎** — SSE 流式输出，多模型适配（OpenAI 兼容 / Anthropic / 自部署）
- 📚 **RAG 知识库** — 文档自动解析、语义 + 全文混合检索，可选 BGE 精排
- 🧭 **DIT 意图路由** — 关键词预筛 + LLM 二次分类，自动 Slot 填充后调用工具
- 👥 **人机协作** — AI 会话可无缝转接人工坐席，实时 WebSocket 双向通信
- 🔐 **RBAC 权限** — 基于 Sa-Token + 部门数据域的细粒度权限体系
- 📊 **数据看板** — 会话趋势、坐席效率、意图分布等多维度统计

## ✨ 核心功能亮点

### 🤖 多模型 AI 对话

- 统一接入层，兼容所有 OpenAI API 格式的推理服务（天翼云 CtyunAI、本地 Ollama、Azure OpenAI 等）
- SSE 流式输出，访客侧体验接近 ChatGPT 实时打字效果
- 支持 MCP（Model Context Protocol）工具扩展，可接入任意第三方工具服务
- 会话历史持久化，AI 可在多轮对话中保持上下文

### 📚 RAG 知识库

- **多格式文档摄入**：PDF、Word（DOCX）、HTML 等，基于 Apache PDFBox / POI / Jsoup 解析
- **智能分块**：Token 感知分块（512 tokens，50 token 滑动窗口），保留语义完整性
- **向量化存储**：1024 维向量写入 pgvector，HNSW 索引（m=16, ef=64，余弦距离）
- **混合检索**：pgvector 语义召回 + PostgreSQL 全文检索（支持 `simple` / `jieba` 分词），可选 BGE-reranker 精排
- **异步摄入流水线**：上传即返回，通过 RabbitMQ + DLQ 保证可靠处理，支持失败重试

### 🧭 DIT 意图路由引擎

Domain → Intent → Tool 三层路由结构，是 ARIA 的核心编排大脑：

| 步骤 | 说明 |
|------|------|
| 1. 关键词快速匹配 | 低延迟预筛，命中则直接走对应 Intent |
| 2. LLM 语义分类 | 关键词未命中时，由 LLM 判断 Domain + Intent |
| 3. Slot 填充 | Intent 有缺少必填槽位时，多轮引导用户补全 |
| 4. Tool 调用 | HTTP Tool / MCP Tool 执行后端业务 API |
| 5. 跨域切换 | 会话可随时切换 Domain，历史记录在 `domain_switch` 表归档 |

### 👥 人机协作

- 访客可主动请求转人工，或 AI 判断复杂度后自动转接
- 坐席通过 WebSocket 实时接收访客消息，双向通信延迟 < 100ms
- 多实例部署时，Redisson Pub/Sub 跨节点消息广播，WebSocket 连接无需同节点
- 坐席可获取 AI 生成的会话摘要和回复建议，辅助高效处理

### 🔐 身份认证与权限

- **JWT 无状态 + Redis 会话**：Sa-Token 双模式，支持前台访客 Token 和后台管理 Token
- **RBAC**：用户 → 角色 → 菜单 / 权限码，细粒度到按钮级别
- **数据域**：部门树 + `DataScopeAspect` AOP，不同角色只能看到自己管辖数据
- **安全特性**：BCrypt 密码加密、登录频率限制、MFA 支持

### 📊 运营看板

- 会话总量 / 趋势、消息量趋势
- 坐席工作负载、平均响应时长
- 意图分布、会话复杂度分布
- 标签云、近期会话列表

## 🏗 系统架构

### 整体拓扑

```
                          ┌─────────────────────────────┐
                          │          Internet            │
                          └──────────────┬──────────────┘
                                         │ HTTPS / WSS
                          ┌──────────────▼──────────────┐
                          │      Nginx 1.27 (反向代理)    │
                          │  ip_hash · WebSocket升级 · TLS│
                          └──┬──────────┬──────────┬────┘
                             │          │          │
               ┌─────────────▼──┐  ┌────▼──────┐  ┌▼──────────────────┐
               │  auth-service  │  │knowledge- │  │ conversation-      │
               │    :8083       │  │ service   │  │ service ×2         │
               │  认证 · RBAC   │  │  :8081    │  │ :8082 (ip_hash)    │
               │  模型配置中心  │  │  RAG流水线│  │ AI对话 · 人工坐席  │
               └────────┬───────┘  └─────┬─────┘  └────────┬───────────┘
                        │                │                  │
          ┌─────────────┼────────────────┼──────────────────┤
          │             │                │                  │
   ┌──────▼──────┐ ┌────▼──────┐  ┌─────▼──────┐  ┌───────▼──────┐
   │ PostgreSQL  │ │  Redis 7  │  │ RabbitMQ   │  │    MinIO     │
   │    :5432    │ │   :6379   │  │  :5672     │  │   :9000      │
   │ pgvector    │ │ Sa-Token  │  │ 文档摄入队列│  │ 原始文件存储  │
   │ 向量索引    │ │ Redisson  │  │ 消息持久化  │  │ (S3兼容)     │
   └─────────────┘ └───────────┘  └────────────┘  └──────────────┘
```

### 服务职责

| 服务 | 端口 | 职责 |
|------|------|------|
| `auth-service` | 8083 | 统一认证、RBAC 权限、AI 模型配置管理 |
| `knowledge-service` | 8081 | 文档解析、向量化、混合检索 RAG 服务 |
| `conversation-service` | 8082 | AI 对话、人工接管、DIT 路由、看板统计 |

### 代码分层（DDD）

每个可运行服务均遵循四层 DDD 架构：

```
interfaces/          ← REST Controller、WebSocket Handler、SSE
application/         ← 用例编排、DTO、事件监听
domain/              ← 聚合根、领域服务、领域事件（零框架依赖）
infrastructure/      ← 数据库、MQ、AI 客户端、外部 HTTP 调用
```

### 多实例 WebSocket 集群

conversation-service 支持水平扩展，关键机制：

```
访客 ──WebSocket──► 实例 A
                       │
坐席 ──WebSocket──► 实例 B
                       │
              Redisson Pub/Sub
           (Redis 跨实例消息广播)
                       │
         实例 A 收到事件 → 推送给访客
```

Nginx 对 `/ws/` 路径启用 `ip_hash`，同一用户粘滞到同一实例；若实例重启，Redisson 确保消息依然可达另一实例上的连接。

## 🛠 技术栈

| 分类 | 技术选型 |
|------|---------|
| **语言 / 运行时** | Java 17 (Eclipse Temurin JRE) |
| **Web 框架** | Spring Boot 3.3.5（MVC + WebFlux 混用） |
| **ORM** | MyBatis-Plus 3.5.7 |
| **认证** | Sa-Token 1.39.0（JWT 模式 + Redis 会话） |
| **数据库** | PostgreSQL 16 + pgvector 扩展 |
| **缓存 / 分布式锁** | Redis 7 + Redisson 3.27.2 |
| **消息队列** | RabbitMQ 3.13（Publisher Confirms + DLQ） |
| **对象存储** | MinIO（S3 兼容接口） |
| **AI / LLM** | LangChain4j 1.1.0（OpenAI 兼容、Anthropic、MCP 协议） |
| **文档解析** | Apache PDFBox 3.0.3 · Apache POI 5.3.0 · Jsoup 1.18.3 |
| **弹性 / 重试** | Resilience4j 2.2.0 · Spring Retry |
| **本地缓存** | Caffeine |
| **HTTP 客户端** | OkHttp 4.12.0 · WebFlux WebClient |
| **API 文档** | SpringDoc OpenAPI 2.6.0（Swagger UI） |
| **数据库迁移** | Flyway 10.15.0 |
| **反向代理** | Nginx 1.27（ip_hash · WebSocket 升级 · TLS） |
| **容器化** | Docker Compose · eclipse-temurin:17-jre-alpine |
| **可观测性** | Micrometer + Brave（全链路 TraceId，MQ 传播） |

## 🚀 快速开始

### 前置依赖

| 依赖 | 版本要求 | 说明 |
|------|---------|------|
| Docker & Docker Compose | ≥ 24.0 | 用于启动基础设施 |
| Java | 17 | 本地开发编译 |
| Maven | ≥ 3.9 | 构建工具 |

### 一、克隆仓库

```bash
git clone https://github.com/your-org/aria-server.git
cd aria-server
```

### 二、启动本地基础设施

使用预置的 Docker Compose 一键拉起 PostgreSQL（含 pgvector）、Redis、RabbitMQ、MinIO：

```bash
docker compose -f deploy/docker-compose-local.yml up -d
```

> 首次启动会自动拉取镜像，pgvector 扩展已在 `aria_knowledge` 数据库中预装。

### 三、初始化数据库

```bash
# 各服务会在首次启动时自动执行 Flyway 迁移
# 也可手动执行 SQL 快照（在 docs/sql/ 目录下）：
psql -U aria -d aria_cs       -f docs/sql/auth_schema.sql
psql -U aria -d aria_cs       -f docs/sql/conversation_schema.sql
psql -U aria -d aria_knowledge -f docs/sql/knowledge_schema.sql
```

### 四、配置 AI 模型

编辑 `ai-auth/auth-service/src/main/resources/application.yml`，或启动后在管理界面 **系统设置 → AI 模型** 中添加模型配置（支持任意 OpenAI 兼容端点）：

```yaml
# 示例：接入天翼云 DeepSeek-V4-Flash
ai:
  default-model:
    api-key: your-api-key
    base-url: https://llm.ctyun.cn/openai/v1
    model: DeepSeek-V4-Flash
```

### 五、启动服务

```bash
# 方式一：使用脚本一键启动（推荐本地开发）
chmod +x start-local.sh && ./start-local.sh

# 方式二：分别启动各服务
mvn -pl ai-auth/auth-service         spring-boot:run
mvn -pl ai-knowledge/knowledge-service spring-boot:run
mvn -pl ai-conversation/conversation-service spring-boot:run
```

### 六、访问 API 文档

| 服务 | Swagger UI |
|------|-----------|
| auth-service | http://localhost:8083/swagger-ui.html |
| knowledge-service | http://localhost:8081/swagger-ui.html |
| conversation-service | http://localhost:8082/swagger-ui.html |

预置种子账号（首次登录后建议修改密码）：

| 账号 | 密码 | 角色 |
|------|------|------|
| `superadmin` | `Test@123456` | 超级管理员 |
| `kfmanager` | `Test@123456` | 客服管理员 |
| `kfstaff` | `Test@123456` | 普通客服 |

## 📦 模块说明

```
aria-server/
├── ai-common/                   # 公共库（无需独立部署）
│   ├── common-core/             # 领域原语：Result、PageResult、异常体系、工具类
│   ├── common-web/              # Web 自动配置：CORS、Sa-Token、全局异常处理
│   └── common-client/           # HTTP 客户端工具：内部鉴权拦截器、Webhook 支持
│
├── ai-auth/                     # 认证与配置服务
│   ├── auth-client/             # SDK：供其他服务拉取 AI 模型配置
│   └── auth-service/            # 可运行服务，端口 8083
│       ├── interfaces/rest/     # 登录、用户、角色、菜单、模型配置 API
│       ├── application/         # 用例编排
│       ├── domain/              # 用户/角色/部门聚合，数据域规则
│       └── infrastructure/      # Sa-Token 集成、BCrypt、登录限流、内部 API 鉴权
│
├── ai-knowledge/                # 知识库服务
│   ├── knowledge-client/        # SDK：供 conversation-service 调用内部搜索接口
│   └── knowledge-service/       # 可运行服务，端口 8081
│       ├── interfaces/rest/     # 文档上传、检索测试、Chunk 管理 API
│       ├── application/         # RAG 编排、异步摄入用例
│       ├── domain/              # 知识库、文档、Chunk 聚合
│       └── infrastructure/      # 解析器、分块器、Embedding、MinIO、MQ 消费者、重排序
│
├── ai-conversation/             # 对话服务
│   └── conversation-service/    # 可运行服务，端口 8082
│       ├── interfaces/rest/     # 访客 Chat、坐席队列、DIT 管理、看板 API
│       ├── application/         # AI 对话流程、路由策略、工具调度
│       ├── domain/              # 会话、消息、意图聚合
│       └── infrastructure/      # WebSocket、DIT 引擎、AI 包装器、MQ、知识库调用
│
├── deploy/                      # 部署配置
│   ├── docker-compose.yml       # 生产环境 Compose
│   ├── docker-compose-local.yml # 本地开发 Compose
│   └── chat.conf                # Nginx 虚拟主机配置
│
└── docs/
    └── sql/                     # 各服务完整 Schema SQL 快照
```

### common-core 提供的公共类型

| 类型 | 说明 |
|------|------|
| `Result<T>` | 统一 API 响应包装，包含 code / message / data |
| `PageResult<T>` | 分页响应，包含 total / list / current / size |
| `BizException` | 业务异常基类，携带错误码 |
| `PageUtil` | MyBatis-Plus 分页工具 |

## 📡 API 概览

完整接口文档请访问各服务的 Swagger UI，以下为主要端点速查。

### auth-service（:8083）

```
# 认证
POST   /api/v1/auth/login                  用户名密码登录
POST   /api/v1/auth/logout                 登出
POST   /api/v1/auth/refresh                刷新 Token
GET    /api/v1/auth/me                     当前用户信息
GET    /api/v1/auth/codes                  当前用户权限码列表

# 用户管理
GET    /api/v1/users                       用户列表（分页）
POST   /api/v1/users                       新建用户
PUT    /api/v1/users/{id}                  更新用户
DELETE /api/v1/users/{id}                  删除用户
POST   /api/v1/users/{id}/roles            分配角色

# 角色 & 菜单
GET|POST|PUT|DELETE /api/v1/roles          角色 CRUD
GET|POST|PUT|DELETE /api/v1/menus          菜单 CRUD

# AI 模型管理（管理员）
GET    /api/v1/admin/ai-models             模型列表
POST   /api/v1/admin/ai-models             新建模型配置
PUT    /api/v1/admin/ai-models/{id}/default 设为默认模型
POST   /api/v1/admin/ai-models/{id}/test   连通性测试
```

### knowledge-service（:8081）

```
# 文档管理
POST   /api/knowledge/docs/upload          上传文档（异步摄入）
GET    /api/knowledge/docs                 文档列表
GET    /api/knowledge/docs/{id}/status     摄入状态
PUT    /api/knowledge/docs/{id}/review     审核通过 / 拒绝
POST   /api/knowledge/docs/{id}/retry      重试失败摄入
DELETE /api/knowledge/docs/{id}            删除文档

# Chunk 管理
GET    /api/knowledge/docs/{id}/chunks     查看文档分块
PUT    /api/knowledge/chunks/{id}/content  手动编辑分块（自动重新向量化）
POST   /api/knowledge/chunks/qa            创建 Q&A 问答对

# 调试
POST   /api/knowledge/docs/search-test     混合检索调试
```

### conversation-service（:8082）

```
# 访客对话
POST   /api/v1/chat/stream                 SSE 流式 AI 回复（text/event-stream）
POST   /api/v1/chat                        阻塞式 AI 回复
GET    /api/v1/chat/history                历史消息
POST   /api/v1/chat/transfer               请求转人工

# 访客身份认证
POST   /api/v1/chat/auth/sms/send          发送 OTP
POST   /api/v1/chat/auth/sms/verify        验证 OTP → 访客 Token

# 坐席队列
GET    /api/v1/sessions                    待处理 / 进行中队列
POST   /api/v1/sessions/{id}/accept        坐席接入
POST   /api/v1/sessions/{id}/close         关闭会话
GET    /api/v1/sessions/events             SSE 实时队列推送（坐席端）
GET    /api/v1/sessions/{id}/ai-summary    AI 生成会话摘要
POST   /api/v1/sessions/{id}/reply-suggestions  AI 辅助回复建议

# DIT 管理（管理员）
GET|POST|PUT|DELETE /api/v1/admin/dit/domains    域管理
GET|POST|PUT|DELETE /api/v1/admin/dit/intents    意图管理
GET|POST|PUT|DELETE /api/v1/admin/dit/tools      工具管理
GET|POST|DELETE     /api/v1/admin/dit/bindings   意图-工具绑定

# 数据看板
GET    /api/v1/dashboard/overview           总览统计
GET    /api/v1/dashboard/conversation-trends 会话趋势
GET    /api/v1/dashboard/agent-workload      坐席负载

# WebSocket
ws://host/ws/chat/{sessionId}              访客实时通信
ws://host/ws/agent                         坐席双向通道
```

### 内部 API（服务间调用）

内部接口通过 `X-Internal-Secret` 请求头鉴权，不对外暴露：

```
# auth-service 内部
GET  /internal/auth/verify                 验证 Token
GET  /internal/ai-models/active            获取当前激活模型配置

# knowledge-service 内部
POST /internal/knowledge/search            混合检索（conversation-service 调用）
POST /internal/knowledge/rerank            结果精排
```

## 🐳 部署指南

### 生产环境（Docker Compose）

```bash
# 1. 构建所有服务镜像
mvn clean package -DskipTests
docker compose -f deploy/docker-compose.yml build

# 2. 启动全部服务
docker compose -f deploy/docker-compose.yml up -d

# 3. 查看服务状态
docker compose -f deploy/docker-compose.yml ps
```

生产 Compose 包含以下容器：

| 容器 | 镜像 | 职责 |
|------|------|------|
| `nginx` | nginx:1.27-alpine | 反向代理 + TLS 终止 |
| `auth-service` | aria/auth-service:latest | 认证服务 |
| `knowledge-service` | aria/knowledge-service:latest | 知识库服务 |
| `conversation-service` | aria/conversation-service:latest | 对话服务（可水平扩展） |
| `postgres` | pgvector/pgvector:pg16 | 数据库（含向量索引） |
| `redis` | redis:7-alpine | 缓存 + 分布式锁 |
| `rabbitmq` | rabbitmq:3.13-management | 消息队列 |
| `minio` | minio/minio:latest | 对象存储 |

### Nginx 配置要点

```nginx
# deploy/chat.conf 关键片段

# 访客 / 坐席 WebSocket — ip_hash 保证同一用户粘滞
upstream conversation_ws {
    ip_hash;
    server conversation-service-1:8082;
    server conversation-service-2:8082;
}

# WebSocket 升级头
proxy_http_version 1.1;
proxy_set_header Upgrade $http_upgrade;
proxy_set_header Connection "upgrade";
proxy_read_timeout 3600s;  # 长连接超时

# API 路由
location /api/v1/auth/     { proxy_pass http://auth-service:8083; }
location /api/knowledge/   { proxy_pass http://knowledge-service:8081; }
location /api/v1/          { proxy_pass http://conversation_upstream; }
location /ws/              { proxy_pass http://conversation_ws; }
```

### conversation-service 水平扩展

```bash
# 启动 2 个 conversation-service 副本
docker compose -f deploy/docker-compose.yml up -d \
    --scale conversation-service=2
```

多副本间通过 **Redisson Pub/Sub** 广播 WebSocket 事件，无需 Session Affinity 即可保证消息送达。

### 健康检查

```bash
curl http://localhost:8083/actuator/health  # auth-service
curl http://localhost:8081/actuator/health  # knowledge-service
curl http://localhost:8082/actuator/health  # conversation-service
```

### 日志

所有服务日志输出到 stdout，Docker 会自动收集。如需持久化：

```bash
# 查看实时日志
docker compose logs -f conversation-service

# 日志目录挂载（已在 compose 中预配置）
volumes:
  - ./logs/auth:/app/logs
  - ./logs/knowledge:/app/logs
  - ./logs/conversation:/app/logs
```

日志包含完整 **TraceId**（Micrometer Brave），跨服务调用可通过同一 TraceId 串联，MQ 消息也会携带 tracing 上下文。

## ⚙️ 配置参考

各服务的配置通过 `application.yml` 管理，支持 Spring Profile（`dev` / `prod`）覆盖。

### auth-service（application.yml 关键项）

```yaml
server:
  port: 8083

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/aria_cs?currentSchema=cs_auth
    username: aria
    password: ${DB_PASSWORD}
  data:
    redis:
      host: localhost
      port: 6379
      password: ${REDIS_PASSWORD}

sa-token:
  token-name: Authorization
  timeout: 86400          # Token 有效期（秒）
  is-concurrent: true
  is-share: false
  token-style: uuid

aria:
  internal:
    secret: ${INTERNAL_SECRET}   # 服务间内部 API 鉴权密钥
  security:
    login-rate-limit:
      max-attempts: 5
      window-seconds: 300
```

### knowledge-service（application.yml 关键项）

```yaml
server:
  port: 8081

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/aria_knowledge
  rabbitmq:
    host: localhost
    port: 5672
    username: aria
    password: ${MQ_PASSWORD}

minio:
  endpoint: http://localhost:9000
  access-key: ${MINIO_ACCESS_KEY}
  secret-key: ${MINIO_SECRET_KEY}
  bucket: knowledge-docs

aria:
  knowledge:
    chunk-size: 512         # 每个 Chunk 最大 Token 数
    chunk-overlap: 50       # 滑动窗口重叠 Token 数
    embedding-dim: 1024     # 向量维度（需与模型一致）
    search:
      vector-weight: 0.7    # 混合检索：向量分数权重
      text-weight: 0.3      # 混合检索：全文分数权重
      top-k: 20             # 召回数量
    reranker:
      enabled: false        # 是否启用 BGE 精排
      endpoint: http://reranker:8000/rerank
```

### conversation-service（application.yml 关键项）

```yaml
server:
  port: 8082

aria:
  auth:
    service-url: http://auth-service:8083   # auth-service 地址
    internal-secret: ${INTERNAL_SECRET}
  knowledge:
    service-url: http://knowledge-service:8081
  conversation:
    ai:
      system-prompt: |
        你是 ARIA，一名专业的 AI 客服助手。请用简洁、友好的语言回答用户问题。
      max-history-turns: 20          # 携带进 Prompt 的最大历史轮数
      stream-timeout-seconds: 60
    websocket:
      heartbeat-interval: 30        # WebSocket 心跳间隔（秒）
    dit:
      keyword-match-threshold: 0.8  # 关键词匹配分数阈值
      llm-classify-enabled: true    # 是否启用 LLM 兜底分类
```

### 环境变量速查

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `DB_PASSWORD` | PostgreSQL 密码 | — |
| `REDIS_PASSWORD` | Redis 密码 | — |
| `MQ_PASSWORD` | RabbitMQ 密码 | — |
| `MINIO_ACCESS_KEY` | MinIO Access Key | — |
| `MINIO_SECRET_KEY` | MinIO Secret Key | — |
| `INTERNAL_SECRET` | 服务间内部 API 鉴权密钥 | — |
| `SPRING_PROFILES_ACTIVE` | 激活 Profile（`dev`/`prod`） | `dev` |

> 生产环境建议通过 Docker Secret 或 Kubernetes Secret 注入敏感配置，不要将密码硬编码在配置文件中。

## 🤝 贡献指南

欢迎所有形式的贡献，包括 Bug 报告、功能建议、文档改进和代码 PR。

### 开发流程

```bash
# 1. Fork 仓库并克隆到本地
git clone https://github.com/your-username/aria-server.git
cd aria-server

# 2. 创建功能分支（从 main 分支）
git checkout -b feature/your-feature-name

# 3. 启动本地环境
docker compose -f deploy/docker-compose-local.yml up -d
./start-local.sh

# 4. 开发 & 提交
git commit -m "feat(module): 简短描述"

# 5. 推送并创建 Pull Request
git push origin feature/your-feature-name
```

### Commit 规范

遵循 [Conventional Commits](https://www.conventionalcommits.org/)：

```
feat(scope):    新功能
fix(scope):     Bug 修复
refactor(scope): 重构（不影响功能）
docs(scope):    文档更新
chore(scope):   构建 / 依赖 / 配置变更
test(scope):    测试相关
```

`scope` 取值：`auth` · `knowledge` · `conversation` · `common` · `deploy` · `sql`

### 提交 PR 前的检查清单

- [ ] 本地所有测试通过：`mvn test`
- [ ] 无明显 Lint / Checkstyle 报错
- [ ] 新增功能包含对应的单元测试
- [ ] 涉及 API 变更已同步更新 Swagger 注解
- [ ] 涉及数据库变更已新增 Flyway 迁移脚本

### 报告 Bug

请在 [Issues](https://github.com/your-org/aria-server/issues) 中提交，包含：

1. 复现步骤（越详细越好）
2. 期望行为 vs 实际行为
3. 环境信息（Java 版本、OS、Docker 版本）
4. 相关日志（去除敏感信息后）

---

## 📄 License

本项目基于 [MIT License](LICENSE) 开源。

```
MIT License

Copyright (c) 2025 ARIA Contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.
```

---

## 🙏 致谢

ARIA 站在以下优秀开源项目的肩膀上：

- [LangChain4j](https://github.com/langchain4j/langchain4j) — Java 生态最活跃的 LLM 集成框架
- [pgvector](https://github.com/pgvector/pgvector) — PostgreSQL 原生向量扩展
- [Sa-Token](https://github.com/dromara/sa-token) — 轻量级 Java 权限认证框架
- [MyBatis-Plus](https://github.com/baomidou/mybatis-plus) — MyBatis 增强工具
- [Redisson](https://github.com/redisson/redisson) — Redis Java 客户端，分布式锁利器

---

<div align="center">

如果这个项目对你有帮助，欢迎给个 ⭐ Star！

**[报告 Bug](https://github.com/your-org/aria-server/issues)** · **[功能请求](https://github.com/your-org/aria-server/issues)** · **[参与讨论](https://github.com/your-org/aria-server/discussions)**

</div>
