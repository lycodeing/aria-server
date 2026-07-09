# 意图识别分层改造设计

> 文档版本：v1.0  
> 日期：2026-07-10  
> 状态：设计完成，待实现

## 一、背景与现状

### 1.1 当前架构

`LangChain4jIntentService` 是当前唯一的意图识别实现，每次用户消息都通过 `DynamicModelFactory.getChatModel()` 发起一次完整的大模型推理，返回 JSON 格式的意图分类结果。

```
用户消息
    │
    ▼
LangChain4jIntentService.classify()
    │  调用大模型（chatModel）
    ▼
{"intent": "FAQ_QUERY", "confidence": 0.92}
    │
    ▼
ChatAppService 路由
```

### 1.2 存在的问题

**性能问题：**
- 每次意图分类消耗一次完整大模型推理，延迟 **300–700ms**
- FAQ 流程下每轮对话实际发起 **2 次** LLM 调用（意图分类 + 回答生成），占用相同模型资源
- 高并发时意图分类请求和生成请求争抢资源，相互影响

**成本问题：**
- "转人工""投诉"等信号极其明确的消息，使用大模型分类是浪费
- 100% 的消息都走大模型，即使对话量较小也线性产生 token 费用

**扩展性问题：**
- 意图种类完全由 `__system__` 域的 `IntentConfig` 动态配置，但分类引擎不可替换
- 无置信度阈值机制，低质量分类结果直接触发路由

### 1.3 目标

| 维度 | 目标 |
|---|---|
| 明确意图（转人工/投诉）分类延迟 | < 1ms（规则匹配） |
| 普通意图分类延迟 | < 20ms（轻量模型） |
| 大模型分类调用占比 | ≤ 10%（仅兜底） |
| 意图分类 token 消耗 | 减少 ≥ 90% |
| 对 ChatAppService 的影响 | 零改动（仅替换 IntentService Bean） |

## 二、三层架构总览

### 2.1 整体设计

引入 `HybridIntentService` 作为 `IntentService` 的新主实现（`@Primary`），内部串联三层分类器。`ChatAppService` 和 `LangChain4jIntentService` **均不需要修改**。

```
用户消息
    │
    ▼
┌────────────────────────────────────────────────────────────┐
│              HybridIntentService（@Primary）                │
│                                                            │
│  ┌─────────────────────────────────────────────────────┐  │
│  │ Layer 1: RuleIntentClassifier                       │  │
│  │  · 从 IntentConfig.keywords 读取规则（DB 配置）      │  │
│  │  · 关键词命中 → confidence=1.0，< 1ms 直接返回       │  │
│  │  · 未命中 → 传入 Layer 2                            │  │
│  └─────────────────────────────────────────────────────┘  │
│                          │ 未命中                          │
│                          ▼                                 │
│  ┌─────────────────────────────────────────────────────┐  │
│  │ Layer 2: BertIntentClassifier                       │  │
│  │  · 调用 Python FastAPI（fine-tuned RoBERTa）         │  │
│  │  · URL 从 AiModelConfig（INTENT 角色）读取           │  │
│  │  · confidence ≥ 0.80 → 直接返回，< 20ms             │  │
│  │  · 0.65 ≤ confidence < 0.80 → 传入 Layer 3          │  │
│  │  · confidence < 0.65 → 返回 UNKNOWN，不走 Layer 3   │  │
│  └─────────────────────────────────────────────────────┘  │
│                          │ 置信度不足                       │
│                          ▼                                 │
│  ┌─────────────────────────────────────────────────────┐  │
│  │ Layer 3: LangChain4jIntentService（已有，不修改）     │  │
│  │  · 大模型兜底，仅处理边缘模糊 case                   │  │
│  │  · 预计触发率 ≤ 10%                                 │  │
│  └────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────┘
    │
    ▼
IntentResult（现有 domain model，不变）
    │
    ▼
ChatAppService 路由（不变）
```

### 2.2 置信度阈值策略

```
confidence ≥ 0.80  →  Layer 2 直接返回，不走 LLM
0.65 ≤ conf < 0.80 →  交给 Layer 3（LLM 二次判断）
confidence < 0.65  →  直接返回 UNKNOWN，走正常 FAQ 流程
                      （宁可走 FAQ 也不误触发转人工/拒答）
```

### 2.3 Bean 优先级

| Bean | 注解 | 角色 |
|---|---|---|
| `HybridIntentService` | `@Primary @Component` | 主实现，`ChatAppService` 注入此 Bean |
| `LangChain4jIntentService` | `@Component`（去掉或不加 `@Primary`） | Layer 3 兜底，由 `HybridIntentService` 直接持有引用 |
| `RuleIntentClassifier` | `@Component` | Layer 1，内部 Bean |
| `BertIntentClassifier` | `@Component` | Layer 2，内部 Bean |

## 三、Layer 1 — 规则分类器

### 3.1 设计原则

规则跟着意图配置走，**不硬编码在代码里**。关键词存在 `IntentConfig` 的新增字段中，通过管理后台或 DB 维护，无需重新部署即可调整。

### 3.2 IntentConfig 数据模型扩展

在现有 `IntentConfig` record/domain 对象中新增两个字段：

```java
/**
 * 关键词列表，非空时启用规则匹配。
 * 例：["转人工", "找真人", "人工客服"]
 */
List<String> keywords;   // nullable，为空则跳过规则层

/**
 * 关键词匹配模式，默认 ANY_CONTAINS。
 */
KeywordMatchMode keywordMatchMode;  // 枚举，默认值 ANY_CONTAINS
```

**KeywordMatchMode 枚举：**

| 值 | 语义 |
|---|---|
| `ANY_CONTAINS`（默认）| 消息包含任意一个关键词即命中 |
| `ALL_CONTAINS` | 消息必须包含全部关键词（组合词场景） |
| `REGEX` | 单条正则表达式，keywords[0] 为正则内容 |

### 3.3 推荐配置的意图

以下意图关键词信号明确，适合规则层处理：

| 意图 code | 推荐关键词（示例） |
|---|---|
| `TRANSFER_REQUEST` | 转人工、找真人、要真人、人工客服、接人工、转接、要人工 |
| `COMPLAINT` | 投诉、举报、太差了、要求赔偿、差评、不满意、要投诉 |
| `CHITCHAT` | 你好、hello、hi、早上好、晚上好、谢谢、感谢、再见、拜拜 |

`FAQ_QUERY` 和 `OUT_OF_SCOPE` **不适合**关键词规则，交给模型层判断。

### 3.4 匹配逻辑伪代码

```
RuleIntentClassifier.classify(userMessage):
    domain = domainRepository.findByCode(SYSTEM_DOMAIN)
    for each IntentConfig config in domain.intents():
        if config.keywords is empty:
            continue
        matched = match(userMessage, config.keywords, config.keywordMatchMode)
        if matched:
            log("[Intent] Rule命中 intent={} keyword=...", config.code)
            return IntentResult(IntentType.valueOf(config.code), 1.0)
    return null   // 未命中，交给 Layer 2
```

### 3.5 注意事项

- 关键词匹配前对 `userMessage` 做 **trim + 全角转半角** 归一化，避免编码差异导致漏匹配
- 关键词列表从 DB 加载，建议在 `DomainRepository` 层增加短时缓存（30s TTL），避免每次规则匹配都查 DB
- 匹配失败返回 `null`（不是 `IntentResult.UNKNOWN`），让 `HybridIntentService` 明确区分"规则未命中"和"分类为 UNKNOWN"

## 四、Layer 2 — BERT FastAPI 轻量分类器

### 4.1 整体方案

部署一个独立的 Python FastAPI 服务，加载 fine-tuned `hfl/chinese-roberta-wwm-ext` 模型，提供 HTTP 分类接口。Java 侧通过 `WebClient` 调用，超时 500ms，异常自动降级到 Layer 3。

```
Java BertIntentClassifier
    │  HTTP POST /classify  {"text": "用户消息"}
    ▼
Python FastAPI（端口 8090）
    │  fine-tuned chinese-roberta-wwm-ext
    ▼
{"intent": "FAQ_QUERY", "confidence": 0.94}
    │
    ▼
Java BertIntentClassifier 返回 IntentResult
```

### 4.2 Python 服务接口规范

**POST `/classify`**

Request：
```json
{ "text": "我要找真人客服" }
```

Response：
```json
{ "intent": "TRANSFER_REQUEST", "confidence": 0.97 }
```

**GET `/health`**

Response：
```json
{ "status": "ok", "model": "chinese-roberta-wwm-ext", "labels": 6 }
```

### 4.3 模型训练数据要求

| 意图 | 最少样本数 | 备注 |
|---|---|---|
| `FAQ_QUERY` | 200 | 各类业务咨询问法 |
| `TRANSFER_REQUEST` | 100 | 包含隐含转人工表达 |
| `COMPLAINT` | 100 | 包含委婉投诉表达 |
| `CHITCHAT` | 100 | 问候、感谢、闲聊 |
| `OUT_OF_SCOPE` | 100 | 业务无关话题 |
| `UNKNOWN` | 50  | 模糊/无意义输入 |

**数据格式（CSV）：**
```csv
text,label
退款政策是什么,FAQ_QUERY
我要找真人客服,TRANSFER_REQUEST
你们服务太差了,COMPLAINT
你好呀,CHITCHAT
帮我解微积分题,OUT_OF_SCOPE
```

### 4.4 冷启动策略（无标注数据时）

初期无标注数据可先用 **zero-shot 分类**模式启动，准确率约 75–85%，同时在生产中积累数据，再 fine-tune 替换：

```
zero-shot 阶段（准确率 ~80%）
    → 生产运行 2–4 周，收集低置信度 case
    → 人工标注后 fine-tune
    → fine-tune 模型上线（准确率 ~95%）
```

### 4.5 部署规格建议

| 环境 | 规格 | 推理延迟 |
|---|---|---|
| 开发/测试 | CPU，2核4G | ~30ms |
| 生产（中等流量） | CPU，4核8G 或 GPU T4 | 3–10ms |
| 生产（高流量） | GPU，多副本 + 负载均衡 | <5ms |

服务打包为 Docker 镜像，与 Java 服务同机房部署，HTTP 往返开销 < 5ms。

## 五、模型配置管理扩展

### 5.1 设计目标

BERT FastAPI 服务的连接信息（URL、超时、协议）通过现有的 `AiModelConfig` + `AiModelConfigProvider` 体系管理，**与 Router 模型配置并列**，从管理后台统一维护，无需修改代码即可切换。

### 5.2 现有模型角色体系

`DynamicModelFactory` 目前已有两类模型角色：

| 方法 | 角色 | 用途 |
|---|---|---|
| `getChatModel()` | `CHAT`（主模型） | 流式/阻塞对话生成 |
| `getRouterModel()` | `ROUTER`（路由模型） | 域路由小模型 |

### 5.3 新增 INTENT 模型角色

新增第三个角色 `INTENT`，`AiModelConfigProvider` 新增 `getActiveIntent()` 方法：

```java
// AiModelConfigProvider 新增
AiModelConfig getActiveIntent();
```

对应的配置字段与 ROUTER 模型保持一致：

```
baseUrl     → BERT FastAPI 服务地址，如 http://bert-intent:8090
modelName   → 模型标识，如 chinese-roberta-wwm-ext（仅用于日志）
apiProtocol → bert（新增协议标识，见下文）
apiKey      → 可留空（内网服务无需认证）
```

### 5.4 DynamicModelFactory 新增 getIntentServiceUrl()

BERT FastAPI 不是 LangChain4j `ChatModel`，而是一个普通 HTTP 服务，**不需要通过 `LlmModelBuilder` 构建模型实例**。`DynamicModelFactory` 只需暴露连接信息：

```java
/**
 * 获取 INTENT 模型的 baseUrl（BERT FastAPI 服务地址）。
 * 供 BertIntentClassifier 构建 WebClient 时使用。
 */
public String getIntentServiceUrl() {
    return configProvider.getActiveIntent().baseUrl();
}
```

`BertIntentClassifier` 在初始化或每次调用时从此方法获取 URL，支持运行期热切换（切换后台配置 → 下次请求生效）。

### 5.5 管理后台配置示例

管理后台"模型配置"页面新增一条 INTENT 角色记录：

| 字段 | 值（示例） |
|---|---|
| 角色 | INTENT |
| 服务地址 | http://bert-intent-svc:8090 |
| 模型名称 | chinese-roberta-wwm-ext |
| 协议 | bert |
| API Key | （留空） |
| 启用 | ✅ |

**切换场景示例：**
- 开发环境：`http://localhost:8090`（本地 Python 服务）
- 测试环境：`http://bert-intent-test:8090`
- 生产环境：`http://bert-intent-prod:8090`（多副本时配 LB 地址）
- 降级回 LLM：禁用 INTENT 配置 → `BertIntentClassifier` 检测到 URL 为空 → 跳过 Layer 2，直接走 Layer 3

### 5.6 置信度阈值配置

两个阈值也作为可配置项，优先从 INTENT 模型配置的扩展字段读取，不存在时使用默认值：

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `intent.confidence.accept` | `0.80` | ≥ 此值直接返回，不走 LLM |
| `intent.confidence.fallback` | `0.65` | < 此值返回 UNKNOWN，也不走 LLM |

## 六、HybridIntentService 编排逻辑

### 6.1 类结构

```
infrastructure/ai/
├── HybridIntentService.java        ← 新建，@Primary，三层编排主类
├── RuleIntentClassifier.java       ← 新建，Layer 1
├── BertIntentClassifier.java       ← 新建，Layer 2
└── LangChain4jIntentService.java   ← 已有，Layer 3，不修改
```

### 6.2 编排逻辑伪代码

```java
@Primary
@Component
public class HybridIntentService implements IntentService {

    // Layer 1：规则
    private final RuleIntentClassifier ruleClassifier;
    // Layer 2：BERT FastAPI
    private final BertIntentClassifier bertClassifier;
    // Layer 3：LangChain4j 大模型兜底
    private final LangChain4jIntentService llmClassifier;
    // 阈值配置
    private final double acceptThreshold;   // default 0.80
    private final double fallbackThreshold; // default 0.65

    @Override
    public IntentResult classify(String userMessage) {

        // Layer 1: 规则匹配
        IntentResult ruleResult = ruleClassifier.classify(userMessage);
        if (ruleResult != null) {
            log.debug("[Intent] Layer1-Rule 命中 intent={}", ruleResult.intent());
            return ruleResult;
        }

        // Layer 2: BERT 轻量模型
        IntentResult bertResult = bertClassifier.classify(userMessage);
        if (bertResult != null) {
            if (bertResult.confidence() >= acceptThreshold) {
                log.debug("[Intent] Layer2-BERT 命中 intent={} conf={}",
                          bertResult.intent(), bertResult.confidence());
                return bertResult;
            }
            if (bertResult.confidence() < fallbackThreshold) {
                log.debug("[Intent] Layer2-BERT 置信度过低 conf={}，降级 UNKNOWN",
                          bertResult.confidence());
                return IntentResult.UNKNOWN;
            }
            // 0.65 ≤ conf < 0.80：走 Layer 3 二次确认
            log.debug("[Intent] Layer2-BERT 置信度不足 conf={}，交 LLM 二次判断",
                      bertResult.confidence());
        }

        // Layer 3: LLM 兜底（低频，≤ 10%）
        log.debug("[Intent] Layer3-LLM 兜底");
        return llmClassifier.classify(userMessage);
    }
}
```

### 6.3 BertIntentClassifier 关键设计

**WebClient 构建：** 每次调用前从 `DynamicModelFactory.getIntentServiceUrl()` 读取 URL，支持热切换：

```java
// URL 为空时跳过，返回 null 让 HybridIntentService 直接走 Layer 3
public IntentResult classify(String userMessage) {
    String url = modelFactory.getIntentServiceUrl();
    if (url == null || url.isBlank()) {
        log.debug("[Intent] BERT 服务未配置，跳过 Layer 2");
        return null;  // null 表示"未参与分类"，区别于 UNKNOWN
    }
    try {
        Map<?, ?> resp = WebClient.create(url)
                .post().uri("/classify")
                .bodyValue(Map.of("text", userMessage))
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofMillis(500))
                .block();
        // 解析 intent + confidence，返回 IntentResult
        ...
    } catch (Exception e) {
        log.warn("[Intent] BERT 服务异常，跳过 Layer 2: {}", e.getMessage());
        return null;  // 异常也返回 null，触发 Layer 3 兜底
    }
}
```

**返回约定：**
- 正常分类：返回 `IntentResult`（confidence 可高可低，由 `HybridIntentService` 判断阈值）
- 服务不可用 / 超时 / URL 未配置：返回 `null`，`HybridIntentService` 直接走 Layer 3

### 6.4 日志埋点

每层分类都记录 DEBUG 日志，便于排查问题和统计各层命中率：

```
[Intent] Layer1-Rule 命中 intent=TRANSFER_REQUEST
[Intent] Layer2-BERT 命中 intent=FAQ_QUERY conf=0.94
[Intent] Layer2-BERT 置信度不足 conf=0.72，交 LLM 二次判断
[Intent] Layer2-BERT 置信度过低 conf=0.51，降级 UNKNOWN
[Intent] Layer3-LLM 兜底
[Intent] BERT 服务未配置，跳过 Layer 2
[Intent] BERT 服务异常，跳过 Layer 2: Connection refused
```

## 七、文件改动清单

### 7.1 新建文件

| 文件路径 | 说明 |
|---|---|
| `infrastructure/ai/HybridIntentService.java` | 三层编排主类，`@Primary`，替换 `LangChain4jIntentService` 成为主 Bean |
| `infrastructure/ai/RuleIntentClassifier.java` | Layer 1 关键词规则引擎 |
| `infrastructure/ai/BertIntentClassifier.java` | Layer 2 BERT FastAPI HTTP 客户端 |

### 7.2 修改文件

| 文件路径 | 改动内容 |
|---|---|
| `domain/model/IntentConfig.java`（或对应 record） | 新增 `keywords: List<String>` 和 `keywordMatchMode: KeywordMatchMode` 字段 |
| `domain/model/KeywordMatchMode.java` | 新建枚举：`ANY_CONTAINS`、`ALL_CONTAINS`、`REGEX` |
| `infrastructure/ai/DynamicModelFactory.java` | 新增 `intentCache`（Caffeine）和 `getIntentServiceUrl()` 方法 |
| `infrastructure/ai/AiModelConfigProvider`（common 层接口） | 新增 `getActiveIntent()` 方法 |
| DB 迁移脚本（Flyway） | `ai_model_config` 表若有 role 字段，新增 `INTENT` 枚举值；`intent_config` 表新增 `keywords`、`keyword_match_mode` 列 |

### 7.3 不变文件

| 文件路径 | 说明 |
|---|---|
| `infrastructure/ai/LangChain4jIntentService.java` | 保留，去掉 `@Primary`（如有），作为 Layer 3 由 `HybridIntentService` 直接持有 |
| `application/service/ChatAppService.java` | **零改动**，通过 `IntentService` 接口注入，Spring 自动选 `@Primary` 的 `HybridIntentService` |
| `domain/model/IntentResult.java` | 不变 |
| `domain/model/IntentType.java` | 不变 |
| `domain/service/IntentService.java` | 不变 |
| `infrastructure/ai/LangChain4jDomainRoutingService.java` | 不变 |

### 7.4 新增测试文件

| 文件路径 | 测试内容 |
|---|---|
| `infrastructure/ai/RuleIntentClassifierTest.java` | 关键词命中/未命中/ALL_CONTAINS/REGEX 覆盖 |
| `infrastructure/ai/BertIntentClassifierTest.java` | Mock WebClient，测试正常、超时、URL 为空场景 |
| `infrastructure/ai/HybridIntentServiceTest.java` | 三层路由逻辑：规则命中、BERT 高置信度、BERT 低置信度走 LLM、BERT 过低直接 UNKNOWN、BERT 不可用 |

## 八、配置项与部署

### 8.1 application.yml 配置项

```yaml
intent:
  # Layer 2 置信度阈值
  confidence:
    accept: 0.80       # ≥ 此值直接返回，不走 LLM
    fallback: 0.65     # < 此值返回 UNKNOWN，不走 LLM
  # BERT FastAPI 连接（也可通过管理后台 INTENT 模型配置覆盖）
  bert:
    timeout-ms: 500    # HTTP 调用超时，超时则降级 Layer 3
```

管理后台模型配置优先级高于 `application.yml`。当 INTENT 角色模型配置存在且启用时，以管理后台配置的 `baseUrl` 为准。

### 8.2 Python FastAPI 服务部署

**Dockerfile（参考）：**

```dockerfile
FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
EXPOSE 8090
CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8090", "--workers", "2"]
```

**requirements.txt：**

```
fastapi==0.111.0
uvicorn==0.30.1
transformers==4.41.2
torch==2.3.1
pydantic==2.7.1
```

**Docker Compose 示例（开发环境）：**

```yaml
services:
  bert-intent:
    build: ./bert-intent-service
    ports:
      - "8090:8090"
    volumes:
      - ./intent-model:/app/intent-model   # fine-tuned 模型目录
    environment:
      MODEL_PATH: /app/intent-model
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8090/health"]
      interval: 30s
      timeout: 5s
      retries: 3

  conversation-service:
    # 现有 Java 服务
    depends_on:
      bert-intent:
        condition: service_healthy
```

### 8.3 管理后台模型配置入口

在现有"模型配置"页面新增 INTENT 角色配置项：

| 字段 | 开发环境示例 | 生产环境示例 |
|---|---|---|
| 角色（role） | `INTENT` | `INTENT` |
| 服务地址（baseUrl） | `http://localhost:8090` | `http://bert-intent-svc:8090` |
| 模型名称（modelName） | `chinese-roberta-wwm-ext` | `chinese-roberta-wwm-ext` |
| 协议（apiProtocol） | `bert` | `bert` |
| API Key | （留空） | （留空） |

### 8.4 关键词配置入口

在现有"意图配置"管理页面，为每个意图新增 keywords 编辑区（逗号分隔），例：

```
意图：TRANSFER_REQUEST
关键词：转人工,找真人,要真人,人工客服,接人工,转接客服
匹配模式：ANY_CONTAINS
```

无需重启服务，DB 更新后下次请求即生效（依赖 `DomainRepository` 缓存 TTL，默认 30s）。

## 九、错误处理与上线路径

### 9.1 错误处理策略

各层异常均不向上抛出，保证 `ChatAppService` 的主流程不受任何影响：

| 层级 | 异常场景 | 处理方式 |
|---|---|---|
| Layer 1（规则） | 不应发生（纯内存操作） | log warn，返回 null，继续 Layer 2 |
| Layer 1（DB 查询失败） | `DomainRepository` 异常 | log warn，返回 null，继续 Layer 2 |
| Layer 2（BERT 超时） | HTTP 超时 >500ms | log warn，返回 null，继续 Layer 3 |
| Layer 2（连接失败） | FastAPI 服务宕机 | log warn，返回 null，继续 Layer 3 |
| Layer 2（URL 未配置） | INTENT 配置未启用 | log debug，返回 null，继续 Layer 3 |
| Layer 3（LLM 超时/异常） | 大模型不可用 | 返回 `IntentResult.UNKNOWN`（现有行为） |

**核心原则：任意一层故障，整体降级到下一层，最终兜底为 UNKNOWN + 走 FAQ 流程。**

### 9.2 分阶段上线路径

#### 阶段一：规则层上线（第 1 周，零风险）

目标：只上 Layer 1，`HybridIntentService` 先只包 Rule + LLM（跳过 Layer 2）。

步骤：
1. 新建 `RuleIntentClassifier`、`HybridIntentService`（Layer 2 暂时跳过，URL 为空时直接走 Layer 3）
2. 在管理后台为 `TRANSFER_REQUEST`、`COMPLAINT`、`CHITCHAT` 配置关键词
3. 灰度放量 10%，观察意图分类日志，验证规则命中率

预期收益：**减少 30–40% 的 LLM 意图分类调用**（高频明确意图直接命中规则）。

#### 阶段二：BERT FastAPI 上线（第 2–3 周）

目标：部署 Python 服务，接入 Layer 2。

步骤：
1. 准备标注数据（6 类 × 100–200 条），运行 fine-tune 脚本
2. 部署 Python FastAPI 服务，通过 `/health` 确认可用
3. 管理后台新增 INTENT 模型配置，填入服务地址
4. 灰度放量 20%，监控 Layer 2 命中率和延迟
5. 确认 P99 延迟 < 50ms 后全量放开

预期收益：**≥ 80% 的请求在 Layer 1 + Layer 2 完成分类，LLM 调用占比降至 ≤ 20%**。

#### 阶段三：数据迭代（持续）

目标：用生产数据持续优化 fine-tune 模型，将 LLM 兜底率压至 ≤ 10%。

步骤：
1. 每周从日志中提取 Layer 2 置信度 < 0.80 的 case
2. 人工标注后追加到训练集
3. 重新 fine-tune，更新模型文件，重启 FastAPI 服务（热更新，不影响 Java 服务）

### 9.3 验收标准

| 指标 | 目标值 | 验证方式 |
|---|---|---|
| 规则层命中率（转人工/投诉/闲聊） | ≥ 95% | 日志统计 `Layer1-Rule 命中` 占比 |
| BERT 层分类延迟 P99 | < 50ms | APM 监控 `BertIntentClassifier` 耗时 |
| LLM 兜底触发率 | ≤ 20%（阶段二），≤ 10%（阶段三） | 日志统计 `Layer3-LLM 兜底` 占比 |
| 原有测试全部通过 | 100% | `mvn test -pl ai-conversation/conversation-service` |
| ChatAppService 零改动 | 无任何 diff | `git diff ChatAppService.java` 为空 |

### 9.4 回滚方案

回滚无需修改代码，只需管理后台操作：

- **回滚 Layer 2**：禁用 INTENT 模型配置 → `BertIntentClassifier` 检测 URL 为空 → 自动跳过 Layer 2，退回 Rule + LLM 模式
- **回滚 Layer 1**：清空所有意图的 keywords 配置 → 规则层返回 null → 退回纯 LLM 模式
- **完全回滚**：将 `LangChain4jIntentService` 重新标注 `@Primary`，删除 `HybridIntentService` Bean（或通过 Spring Profile 切换）
