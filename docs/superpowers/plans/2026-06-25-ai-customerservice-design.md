# 智能客服系统技术设计文档

> **版本**：v1.0 | **日期**：2026-06-25 | **状态**：草稿

---

## 目录

1. [概览与目标](#1-概览与目标)
2. [系统架构](#2-系统架构)
3. [知识库服务 knowledge-service](#3-知识库服务-knowledge-service)
   - 3.1 文档摄取管道
   - 3.2 文档拆分策略
   - 3.3 文档质量治理
   - 3.4 文档更新逻辑
   - 3.5 向量知识库核心代码
4. [对话服务 conversation-service](#4-对话服务-conversation-service)
   - 4.1 RAG 检索管道
   - 4.2 多模型路由
   - 4.3 多轮对话与记忆管理
   - 4.4 人工转接状态机
   - 4.5 用户身份与会话模式
5. [槽位填充 Slot Filling](#5-槽位填充-slot-filling)
6. [可观测性 Observability](#6-可观测性-observability)
7. [高并发扩容策略](#7-高并发扩容策略)
8. [核心数据模型](#8-核心数据模型)
9. [API 设计](#9-api-设计)
   - 9.1 knowledge-service API
   - 9.2 conversation-service API
   - 9.3 OpenAPI 规范（SpringDoc 配置与接口文档）
   - 9.4 服务间调用设计（knowledge-sdk）
10. [开源方案对比](#10-开源方案对比)
11. [非功能性要求](#11-非功能性要求)
12. [人工座席功能设计](#12-人工座席功能设计)
   - 12.1 座席端核心功能
   - 12.2 座席队列管理
   - 12.3 座席端 API
   - 12.4 实时消息通道
   - 12.5 座席容量控制
13. [DDD 架构规范与工具类封装](#13-ddd-架构规范与工具类封装)
   - 13.1 分层包结构
   - 13.2 各层职责边界与依赖规则
   - 13.3 Domain Service 详细设计
   - 13.4 Repository 模式（违规修正）
14. [RBAC 权限管理系统设计](#14-rbac-权限管理系统设计)
   - 14.1 权限模型总览
   - 14.2 数据库表设计（DDL）
   - 14.3 菜单树设计
   - 14.4 数据权限（Data Scope）
   - 14.5 核心代码实现
   - 14.6 API 设计
   - 14.7 与现有 auth-service 的集成关系
   - 13.5 Application Service 规范（违规修正）
   - 13.6 Assembler 转换层
   - 13.7 工具类统一封装

---

## 1 概览与目标

### 1.1 业务目标

为平台外部用户提供 7×24 小时的 AI 智能客服，覆盖产品咨询、故障排查、工单处理三大场景。当 AI 无法处理时，平滑转接人工座席，全程记录对话上下文，座席无需用户重复描述。

### 1.2 核心指标

| 指标 | 目标值 |
|---|---|
| 意图识别准确率 | ≥ 90% |
| 简单 FAQ P99 响应时长 | < 500ms |
| 普通 RAG 对话 P99 响应时长 | < 2s |
| 忠实度（Faithfulness） | ≥ 0.75 |
| 转人工率 | ≤ 20% |
| 知识库文档摄取吞吐 | ≥ 100 文档/分钟 |

### 1.3 技术选型汇总

| 组件 | 选型 | 理由 |
|---|---|---|
| 语言框架 | Java 17 + Spring Boot 3 | 与平台已有服务统一 |
| 持久化 | PostgreSQL + pgvector | 复用现有 PG，向量扩展零新增组件 |
| 缓存/会话 | Redis Cluster | 已有依赖，存短期记忆和 Embedding 缓存 |
| 消息队列 | Redis Streams | 复用 Redis，文档摄取异步解耦，量大再迁 RabbitMQ |
| Embedding 模型 | BGE-M3（中文优先） | MTEB 中文榜前列，支持稠密+稀疏双路 |
| Reranker | BGE-Reranker-v2 | cross-encoder，精排效果显著优于 bi-encoder |
| 大语言模型 | 多模型路由（Qwen/DeepSeek/GPT-4o） | 按意图复杂度路由，兼顾成本与质量 |
| 认证 | Sa-Token | 与平台统一 |
| 接口文档 | SpringDoc OpenAPI 2.6 | 与平台统一 |

---

## 2 系统架构

### 2.1 模块划分

系统拆分为两个独立服务，遵循平台既有的 `service + sdk` 双子模块模式：

```
ai-customerservice/
├── ai-knowledge/
│   ├── knowledge-service     # 文档摄取、解析、Embedding、向量索引管理
│   └── knowledge-sdk         # 供 conversation-service 调用的检索接口
└── ai-conversation/
    ├── conversation-service  # RAG检索、多模型路由、多轮对话、长期记忆、人工转接
    └── conversation-sdk      # 对外暴露的会话接口（供前端/其他服务调用）
```

**拆分依据**：
- `knowledge-service` 是 CPU/IO 密集型异步服务（文档解析、批量 Embedding），吞吐优先
- `conversation-service` 是低延迟在线服务（P99 < 2s），响应时延优先
- 两者物理隔离，互不争抢资源，可独立水平扩容

### 2.2 整体数据流

```
┌──────────────────────────────────────────────────────────────────┐
│  knowledge-service（离线摄取）                                    │
│                                                                    │
│  文档上传 API                                                      │
│      ↓                                                             │
│  Redis Streams (knowledge:doc:ingest)                             │
│      ↓                                                             │
│  IngestWorker                                                      │
│    ├── MultiFormatParser   (PDF/MD/HTML/工单)                     │
│    ├── RecursiveChunkSplitter (四层递归拆分)                       │
│    ├── ChunkQualityChecker (coherence/density/selfContained)      │
│    ├── BGE-M3 Embedder     (批量向量化)                           │
│    └── VectorStoreWriter   → pgvector                             │
│                                                                    │
│  文档管理 API → 状态机 (DRAFT→REVIEW→PUBLISHED→DEPRECATED)       │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│  conversation-service（在线对话）                                  │
│                                                                    │
│  用户请求（HTTP SSE）                                              │
│      ↓                                                             │
│  IntentRecognizer                                                  │
│      ↓                                                             │
│  SlotFillingService ←→ ConversationContext（Redis）               │
│      ↓ 槽位齐全                                                    │
│  RagPipelineService                                                │
│    ① QueryRewriter        (LLM 改写 + 多变体)                    │
│    ② HybridRetriever      (BM25 + pgvector 双路召回)             │
│    ③ RRF Fusion           (倒数排名融合)                          │
│    ④ BGE-Reranker         (精排 top-20 → top-5)                  │
│    ⑤ ContextCompressor    (提取相关句，减少噪声)                  │
│    ⑥ LLMRouter            (意图路由→多模型+熔断降级)             │
│    ⑦ FaithfulnessChecker  (异步，忠实度评分)                      │
│      ↓                                                             │
│  SSE 流式输出 → 用户                                               │
│      ↓（异步落库）                                                 │
│  RagTrace → PostgreSQL                                             │
│      ↓                                                             │
│  AlertService → 钉钉/邮件告警                                     │
└──────────────────────────────────────────────────────────────────┘
```

---

## 3 知识库服务 knowledge-service

### 3.1 文档摄取管道

文档上传后立即返回，处理完全异步化，通过 Redis Streams 解耦上传与处理。

**处理流程：**

```
POST /api/knowledge/docs/upload
    ↓ 同步（< 10ms）
保存文件到对象存储（OSS/MinIO）
写 DocEntity（status = PENDING）
发消息到 Redis Streams: knowledge:doc:ingest
返回 202 Accepted + docId
    ↓ 异步（后台 Worker）
IngestWorker 拉取消息
    ├── 1. 下载文件
    ├── 2. MultiFormatParser 解析原始文本
    ├── 3. RecursiveChunkSplitter 拆分 chunk
    ├── 4. ChunkQualityChecker 过滤低质量 chunk
    ├── 5. 注入面包屑上下文 + 元数据
    ├── 6. BGE-M3 批量 Embedding（每批 32 个）
    ├── 7. 写入 pgvector
    └── 8. 更新 DocEntity（status = PUBLISHED）
```

**IngestWorker 消费代码（核心逻辑）：**

```java
/**
 * 文档摄取消费者，轮询 Redis Streams 消费待处理消息。
 * 遵循阿里规范：@Scheduled 方法体不超过 5 行，业务逻辑全部委托 IngestService。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngestWorker {

    private final IngestService    ingestService;
    private final RedisTemplate<String, String> redisTemplate;

    private static final String STREAM_KEY  = "knowledge:doc:ingest";
    private static final String GROUP_NAME  = "ingest-group";
    private static final String DLQ_KEY     = "knowledge:doc:ingest:dlq";
    private static final int    BATCH_SIZE  = 10;
    private static final int    MAX_RETRIES = 3;

    /** 每 500ms 拉取一批消息，不阻塞主线程 */
    @Scheduled(fixedDelay = 500)
    public void consume() {
        ingestService.consumeBatch(STREAM_KEY, GROUP_NAME, BATCH_SIZE, MAX_RETRIES, DLQ_KEY);
    }
}

/**
 * 摄取业务 Service，负责消息消费、处理、ACK 及死信转移的完整生命周期。
 * 与 IngestWorker 分离，便于单测和复用。
 * 使用 RedisStreamHelper 封装所有 Redis Streams 操作，不直接调用 redisTemplate。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestService {

    private final DocumentIngestPipeline pipeline;
    private final RedisStreamHelper      streamHelper;     // ✅ 使用封装工具类
    private final KnowledgeDocService    knowledgeDocService;

    /** PEL 消息空闲超过此时长才认领重试 */
    private static final Duration CLAIM_IDLE_THRESHOLD = Duration.ofSeconds(60);
    /** 单次最多认领 pending 消息条数 */
    private static final int      CLAIM_BATCH_SIZE     = 10;

    /**
     * 批量消费 Redis Streams 消息。
     * 分两步处理：
     * 1. 优先处理 PEL 中滞留的旧消息（XCLAIM 重新认领后重试或转 DLQ）
     * 2. 再拉取新消息（ReadOffset.lastConsumed()）
     *
     * @param streamKey  Stream Key
     * @param group      消费者组名
     * @param batchSize  单次拉取条数
     * @param maxRetries 最大投递次数，超过后转入死信队列
     * @param dlqKey     死信队列 Key
     */
    public void consumeBatch(String streamKey, String group,
                              int batchSize, int maxRetries, String dlqKey) {
        String workerId = resolveWorkerId();

        // Step 1：优先处理 PEL 中空闲超时的 pending 消息
        processPendingMessages(streamKey, group, workerId, maxRetries, dlqKey);

        // Step 2：拉取新消息（> 游标，仅返回首次投递的消息）
        List<MapRecord<String, Object, Object>> records =
            streamHelper.readNew(streamKey, group, workerId, batchSize, 200L);

        for (MapRecord<String, Object, Object> record : records) {
            processSingle(record, streamKey, group, dlqKey, maxRetries);
        }
    }

    /**
     * 处理 PEL 中滞留的消息：使用 RedisStreamHelper.claimIdleMessages 认领，
     * 根据 Redis 内置 delivery-count 决定重试还是转 DLQ。
     */
    private void processPendingMessages(String streamKey, String group,
                                         String workerId, int maxRetries, String dlqKey) {
        Map<MapRecord<String, Object, Object>, PendingMessage> claimedMap =
            streamHelper.claimIdleMessages(
                streamKey, group, workerId, CLAIM_IDLE_THRESHOLD, CLAIM_BATCH_SIZE);

        if (claimedMap.isEmpty()) {
            return;
        }

        claimedMap.forEach((record, pending) -> {
            long deliveryCount = pending.getTotalDeliveryCount();
            if (deliveryCount >= maxRetries) {
                // 超过最大重试次数，转 DLQ
                streamHelper.pushToDlq(dlqKey, record.getValue().toString());
                streamHelper.acknowledge(streamKey, group, record.getId());
                markDocFailed(record,
                    new RuntimeException("超过最大重试次数 " + maxRetries
                        + "，投递次数=" + deliveryCount));
                log.error("文档摄取转入 DLQ，recordId={}，投递次数={}",
                    record.getId(), deliveryCount);
            } else {
                // 未超限，重新处理
                processSingle(record, streamKey, group, dlqKey, maxRetries);
            }
        });
    }

    private void processSingle(MapRecord<String, Object, Object> record,
                                String streamKey, String group,
                                String dlqKey, int maxRetries) {
        try {
            DocIngestEvent event = DocIngestEvent.fromRecord(record);
            pipeline.process(event);
            // 处理成功，ACK 确认，消息从 PEL 移除
            streamHelper.acknowledge(streamKey, group, record.getId());
        } catch (Exception e) {
            // 不 ACK，消息留在 PEL，由下次 processPendingMessages 根据 delivery-count 决策重试或 DLQ
            log.warn("文档摄取失败，消息留在 PEL 等待重试，recordId={}", record.getId(), e);
        }
    }

    private void markDocFailed(MapRecord<String, Object, Object> record, Exception e) {
        try {
            String docId = record.getValue().get("docId").toString();
            knowledgeDocService.update(
                new LambdaUpdateWrapper<KnowledgeDocEntity>()
                    .eq(KnowledgeDocEntity::getId, docId)
                    .set(KnowledgeDocEntity::getStatus, DocStatus.FAILED)
            );
        } catch (Exception ex) {
            log.error("标记文档失败状态时异常", ex);
        }
    }

    /** 从环境变量或主机名获取 Worker 唯一标识，支持水平扩展时多实例区分 */
    private String resolveWorkerId() {
        String workerId = System.getenv("WORKER_ID");
        return StringUtils.hasText(workerId) ? workerId
            : InetAddressUtils.getLocalHostName() + "-" + ProcessHandle.current().pid();
    }
}
```

**支持的文档格式与解析工具：**

| 格式 | 解析工具 | 特殊处理 |
|---|---|---|
| Markdown | 原生字符串解析 | 保留标题层级树，按 Heading 拆分 |
| PDF | Apache PDFBox 3.x | 多列排版重组、表格提取、页眉页脚剥离 |
| HTML / 网页 | Jsoup | 提取 `<main>` / `<article>`，去除导航/广告/页脚 |
| Word (.docx) | Apache POI | 段落+标题结构提取 |
| 历史工单 | 直接文本 | 隐私脱敏（手机号/身份证正则替换）后作 chunk |

### 3.2 文档拆分策略

**核心原则**：语义完整性 > 固定长度，宁可 chunk 略长，不可截断关键上下文。

#### 四层递归拆分（所有格式通用兜底策略）

```
优先级  分隔符                      适用场景
1      \n## / \n### / \n####       Markdown 标题边界（最优先保留语义）
2      \n\n                        段落边界
3      \n                          换行边界
4      。！？ / . ! ?              句子边界（最后兜底）

目标 chunk 大小：256 ~ 512 token
chunk 间 overlap：50 token（防止关键信息被截断）
```

#### 按文档类型的差异化策略

**Markdown（产品文档、FAQ）：**
```
解析标题树 → 按 H2/H3 边界切分
同一 H3 下内容 < 256 token → 合并到父 H2
同一 H3 下内容 > 512 token → 递归四层拆分
```

**PDF（规格书、手册）：**
```
PDFBox 提取 → 按页面分块 → 段落重组（合并跨页段落）→ 递归四层拆分
表格单独提取 → 序列化为 Markdown 表格格式存储
图片 → 提取 alt-text / caption，不丢弃
```

**历史工单：**
```
单条工单（问+答）< 512 token → 直接作一个 chunk
超长工单 → 按对话轮次拆分，每轮一个 chunk
批量工单 → LLM 提取结构化问答对，去重后入库
```

#### Parent-Child Chunk 双层存储

检索精度与生成质量的最佳平衡点：

```
存储两份：
  child_chunk（128~256 token）→ 用于向量检索（粒度细，语义准）
  parent_chunk（512~1024 token）→ 用于 LLM 生成（上下文完整）

检索流程：
  向量检索命中 child_chunk
      ↓
  通过 parent_id 回溯 parent_chunk
      ↓
  将 parent_chunk 送入 LLM 生成回答
```

#### Chunk 元数据增强（入库前注入）

```json
{
  "chunkId": "chunk_abc123",
  "docId": "doc_001",
  "parentChunkId": "chunk_parent_001",
  "breadcrumb": "产品手册 > 快速开始 > 安装配置",
  "content": "...",
  "contentVector": [...],
  "sourceUrl": "https://docs.example.com/install",
  "docType": "MARKDOWN",
  "docVersion": "v2.1",
  "effectiveFrom": "2026-01-01",
  "expiresAt": null,
  "hypotheticalQuestions": [
    "如何安装产品？",
    "安装配置的步骤是什么？",
    "怎么快速开始使用？"
  ],
  "retrievalWeight": 1.0,
  "feedbackDownvotes": 0
}
```

`hypotheticalQuestions` 由小模型（Qwen-turbo）在摄取时自动生成，用于 HyDE 检索增强——检索时同时匹配 chunk 内容向量和假设问题向量，提升召回率。

### 3.3 文档质量治理

#### 文档生命周期状态机

```
DRAFT → REVIEW → PUBLISHED → DEPRECATED
  ↑               ↓
  └── 审核退回 ←──┘（人工复核）
```

- `DRAFT`：上传完成，等待审核
- `REVIEW`：审核中（人工或自动）
- `PUBLISHED`：已发布，chunk 进入向量库
- `DEPRECATED`：已下线，chunk 从向量库物理删除

文档内容发生变更（hash 比对）→ 自动退回 `DRAFT`，触发重新摄取，旧 chunk 物理删除（不软删），防止新旧内容混存。

#### Chunk 质量过滤器

入库前对每个 chunk 计算三维质量分，低于阈值不入库，合并到邻近 chunk：

```java
public record ChunkQualityScore(
    double coherence,      // 语义完整性：是否是完整的意思单元（0~1）
    double density,        // 信息密度：是否包含实质内容，过滤纯空话（0~1）
    double selfContained   // 自包含性：脱离上下文能否独立理解（0~1）
) {
    // 低于阈值的 chunk 不入库，合并到相邻 chunk
    public boolean passable() {
        return coherence > 0.6 && density > 0.5 && selfContained > 0.5;
    }
}
```

质量打分调用轻量 LLM（Qwen-turbo），批量处理降低成本。

#### 矛盾检测

新文档入库时，检索现有库中语义最近的 top-5 chunk，用 LLM 判断是否存在逻辑矛盾：

```
新 chunk 入库
    ↓
向量检索现有 top-5 相似 chunk
    ↓
LLM 判断：新旧内容是否存在矛盾？
    ├── 无矛盾 → 正常入库
    └── 有矛盾 → 标记 CONFLICT，通知知识库管理员人工复核
```

#### 用户反馈驱动的质量衰减

```
用户点踩（👎）
    ↓
记录：问题 + 回答 + 命中的 chunk_id + 反馈类型
    ↓
chunk.feedbackDownvotes + 1
    ↓
downvotes ≥ 3 → retrievalWeight × 0.7（检索权重下调）
downvotes ≥ 5 → 通知管理员复核，暂停该 chunk 检索
downvotes ≥ 10 → 自动下线，状态置为 DEPRECATED
```

#### 黄金测试集（Golden QA Set）

维护 200~500 条 `<问题, 标准答案, 来源文档>` 三元组，在以下场景自动触发评估：

- 知识库有文档更新时
- 修改了拆分/检索/Prompt 逻辑时
- 每日定时跑（凌晨低峰期）

评估指标低于基线 → 阻断发布，自动告警。

### 3.4 文档更新逻辑

#### 更新策略：原子全量替换

不做增量 diff（复杂且易出错），采用**原子替换**：新 chunk 全部就绪后再删旧 chunk，检索不中断。

```
用户上传新版文档
    ↓
① 计算新文档 SHA-256 hash
② 与数据库 content_hash 对比
    ├─ hash 相同 → 内容无变化，跳过，返回 "文档内容无变化"
    └─ hash 不同 → 进入更新流程
    ↓
③ 创建新 DocEntity（status=PENDING，version+1）
④ 发送 Redis Streams，异步摄取
    ↓（后台 Worker）
⑤ 走完整摄取管道（解析→拆分→Embedding→写 pgvector）
    ↓
⑥ 原子事务切换：
    - 旧 DocEntity.status → DEPRECATED
    - 新 DocEntity.status → PUBLISHED
    - DELETE FROM knowledge_chunk WHERE doc_id = '旧docId'
    ↓
⑦ 通知管理员："文档 xxx 已更新，生成 N 个新 chunk，删除 M 个旧 chunk"
```

**Mapper 层（仅声明，不在 Service 直接注入）：**

```java
@Mapper
public interface KnowledgeDocMapper extends BaseMapper<KnowledgeDocEntity> {
    // 标准 CRUD 由 BaseMapper 提供
    // 过期文档查询使用 LambdaQueryWrapper，无需自定义 SQL 方法
}

@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunkEntity> {
    // 标准 CRUD 由 BaseMapper 提供
    // 向量检索、全文检索等复杂 SQL 定义在 KnowledgeChunkMapper.xml，方法签名如下：

    /**
     * 向量相似度检索（pgvector <=> 余弦距离）。
     * SQL 在 KnowledgeChunkMapper.xml 中，使用 CDATA 包裹 <=> 运算符。
     */
    List<ChunkHitDO> selectByVector(@Param("vec")  String vec,
                                    @Param("kbId")  String kbId,
                                    @Param("topK")  int    topK);

    /**
     * 全文检索（PostgreSQL ts_rank_cd TF-IDF 近似）。
     * SQL 在 KnowledgeChunkMapper.xml 中，依赖 pg_jieba 扩展。
     */
    List<ChunkHitDO> selectByFullText(@Param("query") String query,
                                      @Param("kbId")  String kbId,
                                      @Param("topK")  int    topK);
}
```

**Service 层（使用 LambdaQueryWrapper，简单条件无需自定义 SQL 方法）：**

```java
public interface KnowledgeDocService extends IService<KnowledgeDocEntity> {
    /** 查询已过期文档（expires_at < today 且 status != DEPRECATED） */
    List<KnowledgeDocEntity> listExpired(LocalDate today);
}

@Service
@RequiredArgsConstructor
public class KnowledgeDocServiceImpl
        extends ServiceImpl<KnowledgeDocMapper, KnowledgeDocEntity>
        implements KnowledgeDocService {

    @Override
    public List<KnowledgeDocEntity> listExpired(LocalDate today) {
        // 简单条件直接用 LambdaQueryWrapper，不需要 Mapper 自定义方法
        return list(new LambdaQueryWrapper<KnowledgeDocEntity>()
            .lt(KnowledgeDocEntity::getExpiresAt, today)
            .ne(KnowledgeDocEntity::getStatus, DocStatus.DEPRECATED.name())
        );
    }
}
```

**业务层（DocUpdateService 只依赖 IService，不碰 Mapper）：**

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class DocUpdateService {

    private final KnowledgeDocService     knowledgeDocService;
    private final KnowledgeChunkService   knowledgeChunkService;

    /**
     * 原子切换新旧文档：新 chunk 就绪后，同一事务内下线旧文档并删除旧 chunk。
     * rollbackFor 保证任意异常均回滚，避免新旧状态撕裂。
     */
    @Transactional(rollbackFor = Exception.class)
    public void atomicSwap(String oldDocId, String newDocId) {
        // 下线旧文档
        knowledgeDocService.update(
            new LambdaUpdateWrapper<KnowledgeDocEntity>()
                .eq(KnowledgeDocEntity::getId, oldDocId)
                .set(KnowledgeDocEntity::getStatus, DocStatus.DEPRECATED.name())
        );
        // 上线新文档
        knowledgeDocService.update(
            new LambdaUpdateWrapper<KnowledgeDocEntity>()
                .eq(KnowledgeDocEntity::getId, newDocId)
                .set(KnowledgeDocEntity::getStatus, DocStatus.PUBLISHED.name())
        );
        // 物理删除旧 chunk，防止新旧混存污染检索结果。
        // ✅ knowledge_chunk.doc_status 冗余字段不变量说明：
        //   - 旧 chunk 直接删除，无需更新 doc_status（删了就没了）
        //   - 新 chunk 在摄取管道 PgVectorStore.toEntity() 中写入时已设置 doc_status = PUBLISHED
        //   - 因此本事务结束后，向量库中只剩新 chunk，且 doc_status 全部为 PUBLISHED，与 knowledge_doc.status 一致
        boolean removed = knowledgeChunkService.remove(
            new LambdaQueryWrapper<KnowledgeChunkEntity>()
                .eq(KnowledgeChunkEntity::getDocId, oldDocId)
        );
        log.info("文档原子替换完成，旧 docId={} 已下线，chunk 删除结果={}", oldDocId, removed);
    }
}
```

#### 定期过期文档清理

```java
/**
 * 定时任务只做调度，业务逻辑全部委托 Service，
 * 遵循阿里规范：@Scheduled 方法体不超过 5 行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocExpiryScheduler {

    private final DocExpiryService docExpiryService;

    /** 每天凌晨 02:00 扫描过期文档并自动下线 */
    @Scheduled(cron = "0 0 2 * * *")
    public void deprecateExpiredDocs() {
        docExpiryService.deprecateExpired(LocalDate.now());
    }
}

@Slf4j
@Service
@RequiredArgsConstructor
public class DocExpiryService {

    private final KnowledgeDocService   knowledgeDocService;
    private final KnowledgeChunkService knowledgeChunkService;

    @Transactional(rollbackFor = Exception.class)
    public void deprecateExpired(LocalDate today) {
        List<KnowledgeDocEntity> expired = knowledgeDocService.listExpired(today);
        if (CollectionUtils.isEmpty(expired)) {
            return;
        }
        List<String> docIds = expired.stream()
            .map(KnowledgeDocEntity::getId)
            .toList();

        // 批量更新状态（一条 SQL，避免 N+1）
        knowledgeDocService.update(
            new LambdaUpdateWrapper<KnowledgeDocEntity>()
                .in(KnowledgeDocEntity::getId, docIds)
                .set(KnowledgeDocEntity::getStatus, DocStatus.DEPRECATED)
        );
        // 批量删除 chunk
        knowledgeChunkService.remove(
            new LambdaQueryWrapper<KnowledgeChunkEntity>()
                .in(KnowledgeChunkEntity::getDocId, docIds)
        );
        log.info("过期文档自动下线完成，共处理 {} 篇", docIds.size());
    }
}
```

---

### 3.5 向量知识库核心代码

#### VectorStore 抽象接口（隔离层）

```java
/**
 * 向量存储抽象接口，隔离底层实现。
 * 当前实现：pgvector；量大时可替换为 Milvus/Qdrant，业务代码无需改动。
 */
public interface VectorStore {
    /** 批量写入或更新 chunk（upsert 语义） */
    void upsert(List<ChunkDocument> chunks);
    /** 删除文档下的所有 chunk */
    void deleteByDocId(String docId);
    /** 向量相似度检索（余弦距离） */
    List<ChunkHit> vectorSearch(float[] queryVector, int topK, String kbId);
}
```

#### Mapper 层（复杂 SQL 全部在 XML，不用 @Select 注解）

```java
// Java 接口只声明方法签名，SQL 逻辑在 KnowledgeChunkMapper.xml
@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunkEntity> {

    /**
     * 向量相似度检索（pgvector <=> 余弦距离）。
     * 单表查询，kb_id / doc_status 冗余在 knowledge_chunk，无需 JOIN。
     * SQL 在 KnowledgeChunkMapper.xml，<=> 运算符用 CDATA 包裹避免 XML 转义。
     */
    List<ChunkHitDO> selectByVector(@Param("vec")  String vec,
                                    @Param("kbId")  String kbId,
                                    @Param("topK")  int    topK);

    /**
     * 全文检索（PostgreSQL TF-IDF 近似）。
     * 单表查询，依赖 pg_jieba 扩展。SQL 在 KnowledgeChunkMapper.xml。
     *
     * ⚠️ 与真正 BM25 的区别：
     *   - 本方法：PostgreSQL 内置 ts_rank_cd，TF-IDF 近似，无需额外扩展
     *   - 真正 BM25：需要 pg_search（ParadeDB 扩展）或外部 Elasticsearch
     *
     * ⚠️ 中文分词依赖：
     *   - 需安装 pg_jieba 扩展（CREATE EXTENSION pg_jieba）才能正确分词
     *   - 未安装时退化为 simple 字典，中文召回率大幅下降
     *   - 部署文档中必须明确 pg_jieba 为必须依赖项
     */
    List<ChunkHitDO> selectByFullText(@Param("query") String query,
                                      @Param("kbId")  String kbId,
                                      @Param("topK")  int    topK);
}
```

**KnowledgeChunkMapper.xml**（放在 `resources/mapper/KnowledgeChunkMapper.xml`）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">

<mapper namespace="com.aidevplatform.knowledge.infrastructure.persistence.mapper.KnowledgeChunkMapper">

    <!-- ResultMap：chunk 查询结果映射（下划线 → 驼峰） -->
    <resultMap id="ChunkHitResult" type="com.aidevplatform.knowledge.infrastructure.persistence.do_.ChunkHitDO">
        <id     property="chunkId"       column="chunk_id"/>
        <result property="docId"         column="doc_id"/>
        <result property="content"       column="content"/>
        <result property="breadcrumb"    column="breadcrumb"/>
        <result property="parentChunkId" column="parent_chunk_id"/>
        <result property="score"         column="score"/>
    </resultMap>

    <!--
        向量相似度检索（pgvector 余弦距离）。
        使用 CDATA 包裹 SQL，避免 <=> 和 < 等运算符被 XML 解析器转义。
        单表查询：kb_id / doc_status 冗余在 knowledge_chunk，性能优于 JOIN。
        idx_chunk_kb_status 部分索引覆盖 WHERE 条件。
    -->
    <select id="selectByVector" resultMap="ChunkHitResult">
        <![CDATA[
        SELECT id              AS chunk_id,
               doc_id,
               content,
               breadcrumb,
               parent_chunk_id,
               1 - (content_vector <=> #{vec}::vector) AS score
        FROM knowledge_chunk
        WHERE kb_id          = #{kbId}
          AND doc_status     = 'PUBLISHED'
          AND retrieval_weight > 0
        ORDER BY content_vector <=> #{vec}::vector
        LIMIT #{topK}
        ]]>
    </select>

    <!--
        全文检索（PostgreSQL TF-IDF 近似，依赖 pg_jieba 扩展）。
        @@ 是 PostgreSQL 全文匹配运算符，CDATA 中不会被 XML 误解析。
    -->
    <select id="selectByFullText" resultMap="ChunkHitResult">
        <![CDATA[
        SELECT id              AS chunk_id,
               doc_id,
               content,
               breadcrumb,
               parent_chunk_id,
               ts_rank_cd(to_tsvector('jieba', content),
                          plainto_tsquery('jieba', #{query})) AS score
        FROM knowledge_chunk
        WHERE kb_id          = #{kbId}
          AND doc_status     = 'PUBLISHED'
          AND retrieval_weight > 0
          AND to_tsvector('jieba', content)
              @@ plainto_tsquery('jieba', #{query})
        ORDER BY score DESC
        LIMIT #{topK}
        ]]>
    </select>

</mapper>
```

#### pgvector 实现（走 Mapper，不走 JdbcTemplate）

```java
/**
 * 基于 pgvector 的向量存储实现。
 * upsert 使用 MyBatis-Plus saveOrUpdateBatch，批量写入每批 32 条。
 * 向量检索 / 全文检索 SQL 定义在 KnowledgeChunkMapper.xml（CDATA 包裹），不外泄到 Service。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PgVectorStore implements VectorStore {

    private final KnowledgeChunkService knowledgeChunkService;
    private final KnowledgeChunkMapper  knowledgeChunkMapper;

    private static final int BATCH_SIZE = 32;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void upsert(List<ChunkDocument> chunks) {
        if (CollectionUtils.isEmpty(chunks)) {
            return;
        }
        List<KnowledgeChunkEntity> entities = chunks.stream()
            .map(this::toEntity)
            .toList();
        // MyBatis-Plus 批量 saveOrUpdate，内部分批执行，默认 1000 条/批，此处显式指定
        knowledgeChunkService.saveOrUpdateBatch(entities, BATCH_SIZE);
        log.info("向量写入完成，chunk 数量={}", entities.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByDocId(String docId) {
        knowledgeChunkService.remove(
            new LambdaQueryWrapper<KnowledgeChunkEntity>()
                .eq(KnowledgeChunkEntity::getDocId, docId)
        );
    }

    @Override
    public List<ChunkHit> vectorSearch(float[] queryVector, int topK, String kbId) {
        String vec = VectorUtils.toStr(queryVector);
        return knowledgeChunkMapper.selectByVector(vec, kbId, topK)
            .stream()
            .map(do_ -> ChunkHit.fromDo(do_, HitSource.VECTOR))
            .toList();
    }

    private KnowledgeChunkEntity toEntity(ChunkDocument chunk) {
        return KnowledgeChunkEntity.builder()
            .id(chunk.getId())
            .docId(chunk.getDocId())
            // ✅ 冗余字段必须显式赋值，避免 NOT NULL 约束违反和检索命中失败
            .kbId(chunk.getKbId())
            .docStatus(DocStatus.PUBLISHED.name())
            .parentChunkId(chunk.getParentChunkId())
            .breadcrumb(chunk.getBreadcrumb())
            .content(chunk.getContent())
            // pgvector 格式 [0.1,0.2,...] 存为字符串，由 TypeHandler 处理
            .contentVector(VectorUtils.toStr(chunk.getVector()))
            .tokenCount(chunk.getTokenCount())
            .metadata(JSON.toJSONString(chunk.getMetadata()))
            .build();
    }
}
```

#### 混合检索 + RRF 融合（Service 层，不接触 Mapper）

```java
/**
 * 混合检索服务：BM25（关键词）+ 向量（语义）双路并行，RRF 融合排序。
 * 业务方只调用此类，不直接接触 VectorStore 或 BM25 实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRetriever {

    private final VectorStore        vectorStore;
    private final KnowledgeChunkMapper chunkMapper;
    private final EmbeddingService   embeddingService;

    /** RRF 融合参数，k=60 为业界通用默认值 */
    private static final int RRF_K = 60;

    /**
     * 混合检索入口。
     *
     * @param query  用户查询（已经过改写）
     * @param kbId   目标知识库 ID
     * @param topK   最终返回条数
     * @return RRF 融合后的 chunk 列表，按相关性降序
     */
    public List<ChunkHit> retrieve(String query, String kbId, int topK) {
        float[] queryVector = embeddingService.encode(query);

        // 并行执行两路检索，互不阻塞
        CompletableFuture<List<ChunkHit>> vectorFuture = CompletableFuture.supplyAsync(
            () -> vectorStore.vectorSearch(queryVector, topK * 2, kbId)
        );
        CompletableFuture<List<ChunkHit>> bm25Future = CompletableFuture.supplyAsync(
            () -> chunkMapper.selectByFullText(query, kbId, topK * 2)
                             .stream()
                             .map(do_ -> ChunkHit.fromDo(do_, HitSource.BM25))
                             .toList()
        );

        List<ChunkHit> vectorHits = vectorFuture.join();
        List<ChunkHit> bm25Hits   = bm25Future.join();
        log.debug("混合检索完成，向量召回={}，BM25召回={}", vectorHits.size(), bm25Hits.size());

        return rrfFusion(vectorHits, bm25Hits, topK);
    }

    /**
     * Reciprocal Rank Fusion 融合两路排序结果。
     * score(d) = Σ 1 / (RRF_K + rank_i(d))
     */
    private List<ChunkHit> rrfFusion(List<ChunkHit> vectorHits,
                                      List<ChunkHit> bm25Hits,
                                      int topK) {
        Map<String, Double> scoreMap = new LinkedHashMap<>();

        for (int i = 0; i < vectorHits.size(); i++) {
            scoreMap.merge(vectorHits.get(i).getChunkId(), 1.0 / (RRF_K + i + 1), Double::sum);
        }
        for (int i = 0; i < bm25Hits.size(); i++) {
            scoreMap.merge(bm25Hits.get(i).getChunkId(), 1.0 / (RRF_K + i + 1), Double::sum);
        }

        // 合并两路，chunkId 去重，向量结果优先
        Map<String, ChunkHit> chunkMap = new LinkedHashMap<>();
        vectorHits.forEach(h -> chunkMap.put(h.getChunkId(), h));
        bm25Hits.forEach(h -> chunkMap.putIfAbsent(h.getChunkId(), h));

        return scoreMap.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(topK)
            .map(e -> chunkMap.get(e.getKey()).withScore(e.getValue()))
            .toList();
    }
}
```

#### 工具类（向量格式转换，避免重复代码）

```java
/**
 * 向量工具类，统一处理 float[] 与 pgvector 字符串格式的互转。
 */
public final class VectorUtils {

    private VectorUtils() {}

    /**
     * 将 float[] 转换为 pgvector 字符串格式，如 [0.1,0.2,0.3]。
     *
     * @param vector embedding 向量
     * @return pgvector 可接受的字符串
     */
    public static String toStr(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) {
                sb.append(",");
            }
        }
        return sb.append("]").toString();
    }
}
```

---

## 4 对话服务 conversation-service

### 4.1 RAG 检索管道

完整的八步管道，每步职责单一，可独立替换：

```
① 查询改写（QueryRewriter）
   用 LLM 补全模糊意图，生成 2~3 个检索变体
   "费用怎么算" → ["产品定价规则", "收费标准是什么", "价格计算方式"]

② 混合检索（HybridRetriever）—— 并行执行
   BM25 关键词召回（稀疏）：精确匹配专有名词、错误码
   pgvector 语义召回（稠密）：捕捉语义相关但措辞不同的内容
   各召回 top-20，共 40 条候选

③ RRF 融合（ReciprocalRankFusion）
   公式：score(d) = Σ 1/(k + rank_i(d))，k=60
   合并两路结果，消除重复，输出统一排序的 top-20

④ BGE-Reranker 精排
   cross-encoder 模型对 <query, chunk> 逐对打分
   精排 top-20 → 保留 top-5
   相关性分数 < 0.3 的 chunk 直接丢弃（宁可没有上下文也不送错的）

⑤ 上下文压缩（ContextCompressor）
   只保留 chunk 中与问题直接相关的句子
   减少噪声 token，节省 LLM 上下文窗口

⑥ Prompt 组装
   系统角色 + 长期记忆摘要 + 检索内容（带编号引用）+ 历史对话（最近 5 轮）+ 用户问题
   强制指令："仅根据以下检索内容回答，无相关内容时回答'我没有找到可靠依据'"

⑦ LLM 路由生成（LLMRouter）
   根据意图复杂度路由到对应模型
   流式输出（SSE），降低用户感知延迟

⑧ 忠实度校验（FaithfulnessChecker）—— 异步执行，不阻塞响应
   将回答拆分为原子声明，逐条验证是否有检索内容支撑
   faithfulness_score < 0.7 → 触发告警 + 标记 trace
```

**检索兜底策略：**

```
过滤后无高质量 chunk（全部 < 0.3 分）
    ↓
不调用 LLM，直接返回：
"抱歉，我没有找到关于这个问题的可靠资料。
 您可以：① 换个方式描述问题  ② 联系人工客服"
    ↓
escalatedToHuman = true，进入转接流程
```

### 4.2 多模型路由

#### 路由决策维度

```java
public enum IntentComplexity {
    /** 简单 FAQ，知识库直接命中，无需推理 */
    SIMPLE,
    /** 需要结合多条文档综合回答 */
    MEDIUM,
    /** 需要多步推理、投诉处理、敏感场景 */
    COMPLEX,
    /** 涉及代码分析、技术排查 */
    CODE
}
```

#### 路由规则表

| 意图类型 | 主模型 | 备用模型 | 典型场景 |
|---|---|---|---|
| SIMPLE | Qwen-turbo / GLM-4-Flash | Qwen-turbo | 价格咨询、功能说明 |
| MEDIUM | Qwen-Plus / DeepSeek-V3 | Qwen-Plus | 多步操作指引、账单核查 |
| COMPLEX | Qwen-Max / GPT-4o | Qwen-Plus | 投诉处理、复杂推理 |
| CODE | DeepSeek-Coder / GPT-4o | Qwen-Plus | 接入报错、SDK 使用 |

#### 路由器实现

```java
@Service
public class LLMRouter {

    public Flux<String> route(String query,
                              List<ChunkHit> chunks,
                              ConversationContext ctx) {
        IntentComplexity complexity = complexityScorer.score(query, chunks);
        ModelConfig model = selectModel(complexity);

        // 熔断器包裹，主模型不可用时自动降级
        return CircuitBreakerFactory.create(model.name())
            .run(
                () -> llmClientFactory.get(model).streamChat(buildPrompt(query, chunks, ctx)),
                throwable -> fallback(complexity, query, chunks, ctx)
            );
    }

    private Flux<String> fallback(IntentComplexity complexity,
                                   String query,
                                   List<ChunkHit> chunks,
                                   ConversationContext ctx) {
        // 降级链：主模型 → 备用模型 → 小模型 → 模板回答 → 转人工
        ModelConfig fallbackModel = selectFallbackModel(complexity);
        if (fallbackModel != null) {
            return llmClientFactory.get(fallbackModel).streamChat(buildPrompt(query, chunks, ctx));
        }
        // 全部不可用 → 触发人工转接
        return Flux.just("当前 AI 服务繁忙，正在为您转接人工客服，请稍候...");
    }
}
```

#### 复杂度打分器

```java
/**
 * @deprecated 本类已被 {@link IntentComplexityDomainService} 取代（第 13.3 节）。
 * 实现阶段直接使用 IntentComplexityDomainService，本类仅保留作概念说明。
 */
@Service
@Deprecated
public class ComplexityScorer {

    public IntentComplexity score(String query, List<ChunkHit> chunks) {
        // 规则优先（快，无 LLM 开销）
        if (isCodeRelated(query))      return IntentComplexity.CODE;
        if (isComplaintRelated(query)) return IntentComplexity.COMPLEX;
        if (chunks.size() >= 4)        return IntentComplexity.MEDIUM;
        // ✅ 修复：先判空再访问 get(0)，空列表时兜底返回 MEDIUM
        if (query.length() < 20
                && !chunks.isEmpty()
                && chunks.get(0).getScore() > 0.85) {
            return IntentComplexity.SIMPLE;
        }
        return IntentComplexity.MEDIUM;
    }
}
```

> ⚠️ 实现阶段请使用 `IntentComplexityDomainService`（第 13.3 节），该版本放置于 `domain/service/` 层，职责归属正确且已包含非空校验，本节 `ComplexityScorer` 不再新建。

### 4.3 多轮对话与记忆管理

#### 两层记忆架构

```
短期记忆（Redis，会话级）         长期记忆（PostgreSQL，用户级）
────────────────────           ────────────────────────────
存储：最近 10 轮对话            存储：跨会话摘要、用户偏好、历史标签
TTL：会话结束后 24h 自动过期     永久保存（用户注销时清除）
用途：多轮追问上下文             用途：个性化服务、冷启动优化
```

#### ConversationContext 数据结构

```java
@Data
public class ConversationContext {
    private String              sessionId;
    private String              userId;
    private Intent              currentIntent;
    private Map<String, String> filledSlots;        // 累积槽位，轮次间不清零
    private List<Message>       recentMessages;     // 最近 10 轮，Redis 存储
    private String              longTermSummary;    // 长期记忆摘要（来自 DB）
    private ConversationStatus  status;             // AI_HANDLING / WAITING_AGENT 等
    private Instant             lastActiveAt;
}
```

#### 长期记忆写入策略

会话结束（用户关闭 / 转人工成功 / 超时）时，异步触发摘要生成：

```
会话结束
    ↓
取本次完整对话记录
    ↓
LLM 生成摘要（≤ 200 字）：
  "用户咨询了产品定价和退款流程，
   对标准版价格有疑虑，最终转人工处理。
   偏好：简洁回答，不喜欢冗长说明。"
    ↓
写入 UserMemory 表（append 模式，保留最近 20 条摘要）
    ↓
下次会话冷启动时，取最近 3 条摘要注入 Prompt
```

#### 上下文窗口管理

防止历史过长超出 LLM 上下文限制：

```
历史对话 token 统计
    ↓
> 3000 token → 保留最近 5 轮 + 长期摘要替代早期轮次
> 5000 token → 仅保留最近 3 轮，压缩摘要
始终保留：系统提示 + 检索内容 + 当前问题（核心不可压缩）
```

### 4.4 人工转接状态机

#### 状态定义

```
AI_HANDLING          AI 正在处理
ESCALATION_PENDING   准备转接（等待确认）
WAITING_FOR_AGENT    在座席队列中等待
AGENT_HANDLING       座席已接入，人工处理中
RESOLVED             会话关闭，写入长期记忆
ABANDONED            超时无人接入，引导用户留言
```

#### 状态转换规则

```
AI_HANDLING
  ├─ faithfulness < 0.7 连续 2 次      ──→ ESCALATION_PENDING
  ├─ 用户主动发送"转人工"/"人工客服"  ──→ ESCALATION_PENDING
  ├─ 命中敏感词（投诉/法律/退款纠纷） ──→ ESCALATION_PENDING
  └─ 检索无结果连续 2 次               ──→ ESCALATION_PENDING

ESCALATION_PENDING
  ├─ 有座席在线     ──→ WAITING_FOR_AGENT（加入优先级队列）
  └─ 无座席在线     ──→ 告知等待时长，仍在 ESCALATION_PENDING

WAITING_FOR_AGENT
  ├─ 座席接入       ──→ AGENT_HANDLING
  └─ 超时 10 分钟   ──→ ABANDONED（引导留言，异步跟进）

AGENT_HANDLING
  └─ 座席关闭       ──→ RESOLVED（异步写长期记忆摘要）

RESOLVED / ABANDONED
  └─ 终态，不再流转
```

#### 转接时的上下文传递

座席接入时自动收到完整上下文包，无需用户重复描述：

```java
public record EscalationContext(
    String   sessionId,
    String   userId,
    String   userName,
    String   escalationReason,       // 转接原因（置信度低/用户主动/敏感词）
    List<Message> conversationHistory, // 完整对话历史
    Map<String, String> filledSlots, // 已收集的槽位信息
    List<ChunkHit> lastRetrievedChunks, // 最后一次检索结果（供座席参考）
    Instant  waitingSince
) {}
```

### 4.5 用户身份与会话模式

#### 设计动机

对话系统需支持两种用户状态：**访客（游客）** 和 **已登录用户**。通用 FAQ 咨询不应强制登录，但涉及账号数据的敏感操作（查订单、申请退款、投诉）必须验证身份。两种状态平滑过渡，不打断对话流。

#### 两种会话模式

```
GUEST（访客）                    AUTHENTICATED（已认证）
─────────────────────────────────────────────────────
会话 ID 绑定临时 token           会话 ID 绑定 user_id
无长期记忆                       长期记忆激活
槽位手动填写                     槽位自动回填（手机号/历史订单）
可访问：通用 FAQ、产品咨询        可访问：全部功能
不可访问：订单查询、退款、投诉    包含敏感操作
```

#### 会话状态字段

```java
// ConversationSession 新增字段
public enum SessionMode { GUEST, AUTHENTICATED }

public class ConversationSession {
    private String      id;
    private String      userId;          // GUEST 时为 null
    private SessionMode mode;            // GUEST | AUTHENTICATED
    private String      guestToken;      // 访客临时 token（UUID，存 Redis，TTL 1h）
    private ConversationStatus status;
    // ...其余字段不变
}
```

#### 升级触发条件

访客模式下，以下操作触发身份验证弹窗，验证成功后会话升级为 AUTHENTICATED：

```
触发条件                         验证方式
─────────────────────────────────────────────
意图识别为 ORDER_QUERY            手机号 + 短信验证码
意图识别为 REFUND_REQUEST         手机号 + 短信验证码
意图识别为 COMPLAINT              手机号 + 短信验证码
用户主动点击"登录"               手机号 + 短信验证码 或 扫码登录
```

#### 升级流程

```
访客提问（ORDER_QUERY 意图）
    ↓
IntentRecognizer 识别意图
    ↓
SessionModeService.requireAuth(session) → 返回 AUTH_REQUIRED
    ↓
前端弹出验证弹窗（手机号 + 短信码）
    ↓
POST /api/conversation/sessions/{id}/authenticate
    ↓
验证成功：
  session.mode  = AUTHENTICATED
  session.userId = user_id（与平台账号打通）
  filledSlots 自动回填手机号等已知信息
  长期记忆加载
    ↓
继续执行原始意图（ORDER_QUERY），不需要用户重复输入
```

#### SessionModeService（Domain Service）

```java
package com.aidevplatform.conversation.domain.service;

/**
 * 会话模式领域服务。
 * 判断当前操作是否需要身份验证，以及会话升级后的状态初始化。
 */
@Service
public class SessionModeService {

    /** 需要身份验证的意图集合 */
    private static final Set<Intent> AUTH_REQUIRED_INTENTS = Set.of(
        Intent.ORDER_QUERY, Intent.REFUND_REQUEST, Intent.COMPLAINT
    );

    /**
     * 判断当前意图是否需要身份验证。
     * GUEST 模式下命中敏感意图 → 返回 true，前端弹验证弹窗。
     */
    public boolean requiresAuth(ConversationSession session, Intent intent) {
        return session.getMode() == SessionMode.GUEST
            && AUTH_REQUIRED_INTENTS.contains(intent);
    }

    /**
     * 会话升级：访客 → 已认证。
     * 绑定 userId，激活长期记忆，自动回填已知槽位。
     *
     * @param session   当前会话
     * @param userId    认证后的用户 ID
     * @param userProfile 用户档案（含手机号等已知信息）
     */
    public void upgrade(ConversationSession session,
                         String userId,
                         UserProfile userProfile) {
        session.setMode(SessionMode.AUTHENTICATED);
        session.setUserId(userId);
        // 自动回填手机号槽位，用户无需重复输入
        if (StringUtils.hasText(userProfile.getPhoneTail())) {
            session.getFilledSlots().put("phone_tail", userProfile.getPhoneTail());
        }
    }
}
```

#### 认证接口

```
POST /api/conversation/sessions/{id}/send-code
     body: { "phone": "138****8888" }
     响应: 202 Accepted（发送短信验证码）

POST /api/conversation/sessions/{id}/authenticate
     body: { "phone": "138****8888", "code": "123456" }
     响应: { "userId": "user_xxx", "mode": "AUTHENTICATED" }
     副作用: 会话升级，长期记忆加载，槽位自动回填
```

#### 安全约束

- 短信验证码 TTL 5 分钟，同一手机号每分钟最多发 1 次
- 连续验证失败 5 次，锁定该手机号 10 分钟
- `guestToken` 存 Redis，TTL 1 小时，超时会话自动失效
- 访客模式下不记录任何用户 PII，仅记录匿名对话内容

---

## 5 槽位填充 Slot Filling

### 5.1 设计动机

意图识别解决"用户想做什么"，槽位填充解决"执行该意图还缺哪些信息"。缺少槽位填充，系统无法处理"帮我查一下订单"（缺订单号）这类常见表达，只能让用户重复描述。

### 5.2 意图→槽位模板映射

```java
public enum IntentSlotTemplate {

    ORDER_QUERY(List.of(
        SlotDefinition.required("order_id",    "订单号",   "\\d{8,}"),
        SlotDefinition.optional("phone_tail",  "手机尾号", "\\d{4}")
    )),

    REFUND_REQUEST(List.of(
        SlotDefinition.required("order_id",     "订单号",   "\\d{8,}"),
        SlotDefinition.required("refund_reason","退款原因", null)  // LLM提取，无正则
    )),

    DELIVERY_QUERY(List.of(
        SlotDefinition.required("order_id", "订单号", "\\d{8,}")
    )),

    PRODUCT_CONSULT(List.of(
        SlotDefinition.required("product_name", "商品名称", null)
    )),

    GENERAL_FAQ(List.of(
        // 无必填槽位，直接进 RAG 检索
    ));
}
```

### 5.3 填充流程

```
用户消息
    ↓
① 从用户档案自动填充（登录用户免重复输入）
  → 手机号、用户 ID 等已知信息预填
    ↓
② 从当前消息提取（正则 + LLM 双保险）
  → 正则：订单号 \d{8,}、手机号 \d{11}（结构化，快）
  → LLM：退款原因、商品描述（非结构化，语义提取）
    ↓
③ 检查必填槽是否全部满足
  ├─ 全部满足 → SlotFillingResult.complete(slots) → 进入 RAG 管道
  └─ 有缺口   → 生成自然语言追问 → 返回给用户
    ↓（追问示例）
"好的，为了帮您查询物流，请问您的订单号是多少？"
（不是："请输入订单号："——机器人腔）
```

### 5.4 上下文累积

槽位值随对话轮次累积，存入 Redis 的 `ConversationContext.filledSlots`，不随轮次清零：

```
第 1 轮：用户说"帮我查订单"
  → 识别缺 order_id，追问

第 2 轮：用户说"123456789"
  → 提取 order_id = "123456789"，存入 filledSlots
  → 槽位齐全，进入 RAG 检索

第 3 轮：用户说"那退款怎么操作？"
  → 意图切换为 REFUND_REQUEST
  → order_id 从 filledSlots 自动复用，无需再问
  → 只追问 refund_reason
```

---

## 6 可观测性 Observability

### 6.1 RagTrace 全链路追踪

每次对话请求产生一条 `RagTrace`，记录从查询改写到最终输出的每个节点结果和耗时，异步落库不阻塞响应。

```java
@Data
@Builder
public class RagTrace {
    private String   traceId;               // UUID，贯穿全链路，注入 MDC
    private String   sessionId;
    private String   userId;
    private String   originalQuery;
    private String   rewrittenQuery;

    // 检索层
    private int      bm25HitCount;
    private int      vectorHitCount;
    private List<String> rerankTopChunkIds; // 精排后 top-5 的 chunk ID

    // 生成层
    private String   selectedModel;
    private int      promptTokens;
    private int      completionTokens;
    private double   faithfulnessScore;     // 0~1，异步填入
    private double   answerRelevancyScore;  // 0~1，异步填入
    private boolean  escalatedToHuman;

    // 耗时拆分（毫秒）
    private long     queryRewriteMs;
    private long     retrievalMs;
    private long     rerankMs;
    private long     generationMs;
    private long     totalMs;

    // 用户反馈（异步填入）
    private Integer  userFeedback;  // 1=满意 0=不满意 null=未反馈
    private Instant  createdAt;
}
```

MDC 注入保证所有日志自动携带 `traceId`，出问题时全链路可追溯：

```java
MDC.put("traceId", trace.getTraceId());
MDC.put("sessionId", sessionId);
// ... 处理完毕后
MDC.clear();
```

### 6.2 告警规则

| 告警名 | 触发条件 | 处置动作 |
|---|---|---|
| 忠实度持续低 | faithfulness < 0.7 连续 10 次 | 钉钉告警 + 自动降级为拒答兜底 |
| 转人工率异常 | 转人工率 > 30%（1 小时窗口） | 告警 + 通知扩充座席 |
| 响应延迟恶化 | P99 > 5s 持续 3 分钟 | 告警 + 自动降级为小模型 |
| Token 成本突增 | 当日消耗 > 均值 200% | 告警 + 审查路由规则 |
| 摄取积压 | Redis Streams PEL 积压 > 500 条 | 告警 + 自动扩 Worker 实例 |

### 6.3 监控 Dashboard 核心指标

```
实时指标（1 分钟粒度）
  ├── 转人工率（%）
  ├── 平均 faithfulness 分
  ├── P50 / P95 / P99 响应时长
  └── 每分钟 Token 消耗及估算成本

离线指标（每日统计）
  ├── 用户满意率（点赞 / 总对话）
  ├── 黄金测试集四维跑分（知识库更新后自动触发）
  ├── Token 成本趋势
  └── Top-20 被踩 chunk（知识库优化依据）
```

### 6.4 RAG vs 微调（Fine-tuning）取舍说明

> 这是面试高频追问，设计中明确立场。

| 维度 | RAG（本系统选择） | Fine-tuning |
|---|---|---|
| 知识时效性 | ✅ 实时更新知识库即可 | ❌ 需要重新训练，周期长 |
| 垂直领域适配 | ✅ 接入专业文档库 | ✅ 深度适配，但成本高 |
| 幻觉风险 | 较低（有检索锚定） | 较高（黑盒，无溯源） |
| 可解释性 | ✅ 每句话有来源引用 | ❌ 无法溯源 |
| 成本 | 低（无训练成本） | 高（GPU 训练 + 数据标注） |
| 本系统结论 | 客服知识频繁更新、需要溯源 → **优先 RAG** | 如未来积累足够高质量对话数据，可用 PEFT 微调 Embedding 模型提升召回率 |

---

## 7 高并发扩容策略

### 7.1 两服务的负载特征差异

| 维度 | knowledge-service | conversation-service |
|---|---|---|
| 负载类型 | CPU/IO 密集，异步批处理 | 低延迟，同步在线 |
| 扩容触发 | Redis Streams 积压量 | 请求 QPS / P99 延迟 |
| 状态 | 无状态（Worker 可随意扩缩） | 无状态（会话存 Redis） |
| 扩容粒度 | Worker 实例数 | Pod 实例数 |

### 7.2 knowledge-service 扩容

```
文档上传
    ↓
Redis Streams: knowledge:doc:ingest
    ↓
IngestWorker 消费者组（可水平扩展）
    Worker-1 ──┐
    Worker-2 ──┤→ 各自独立消费，互不干扰
    Worker-N ──┘

扩容规则：
  PEL（待处理消息）积压 > 200 条 → 扩一个 Worker 实例
  PEL 积压 > 500 条             → 告警 + 继续扩容
  PEL 积压 < 50 条（空闲）      → 缩容到最小 1 实例

摄取与在线物理隔离：
  IngestWorker 独立线程池，不与 HTTP 请求线程竞争
  Embedding API 调用限速时，指数退避重试（1s→2s→4s），不影响在线对话
```

### 7.3 conversation-service 扩容

```
Nginx / K8s Ingress（负载均衡）
    ↓
conversation-service 实例（无状态，水平扩）
    ├── 短期记忆 → Redis Cluster（不在实例本地，支持实例任意扩缩）
    ├── 向量检索 → pgvector 只读副本（专用只读连接池，与写隔离）
    └── LLM 调用 → 连接池 + 熔断器（Resilience4j）
```

### 7.4 四个关键性能优化

**① 查询 Embedding 缓存（最显著，省 50~200ms）**

```java
/**
 * 获取查询向量，优先走 Redis 缓存（Cache-Aside 模式）。
 * key 格式：emb:query:{md5(query)}，TTL 2 小时。
 * 相同或相近问题命中缓存，跳过 Embedding 模型调用，节省 50~200ms。
 */
public float[] getQueryEmbedding(String query) {
    // key 规范：{module}:{type}:{id}
    String key = "emb:query:" + DigestUtils.md5Hex(query.trim().toLowerCase());
    // ✅ 使用 RedisCacheHelper.getOrLoad，Cache-Aside 模式，代码简洁
    return cacheHelper.getOrLoad(key, float[].class, Duration.ofHours(2),
        () -> embeddingClient.encode(query));
}
```

**② 忠实度校验异步化（省 200~500ms 主链路）**

```java
// 先流式返回答案，校验在后台跑
CompletableFuture.runAsync(() -> {
    double score = faithfulnessChecker.check(response, chunks);
    traceRepository.updateFaithfulness(traceId, score);
    if (score < 0.7) alertService.lowFaithfulness(traceId, sessionId);
});
```

**③ SSE 流式输出（降低用户感知延迟）**

```java
@GetMapping(value = "/api/conversation/chat/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter streamChat(@RequestParam String sessionId,
                              @RequestParam String query) {
    SseEmitter emitter = new SseEmitter(30_000L);
    llmRouter.streamGenerate(query, sessionId,
        token -> emitter.send(SseEmitter.event().data(token)),
        ()    -> emitter.complete()
    );
    return emitter;
}
```

**④ 熔断降级链（Circuit Breaker）**

```
主模型超时或错误率 > 20%
    ↓ 熔断
备用模型（低一档）
    ↓ 备用模型也不可用
小模型（Qwen-turbo，始终保底）
    ↓ 全部不可用
模板回答 + 转人工
```

### 7.5 容量目标

| 场景 | P50 | P99 | 备注 |
|---|---|---|---|
| 简单 FAQ（缓存命中） | < 150ms | < 400ms | Embedding 缓存 + 小模型 |
| 普通 RAG 对话 | < 600ms | < 2s | 标准检索 + 中模型，流式输出 |
| 复杂推理/投诉 | < 1s | < 4s | 大模型，流式输出 |
| 文档摄取（3 Worker） | — | — | ≥ 100 文档/分钟 |

---

## 8 核心数据模型

### 8.1 knowledge-service 核心表

```sql
-- 文档表
CREATE TABLE knowledge_doc (
    id             VARCHAR(36)  PRIMARY KEY,
    kb_id          VARCHAR(36)  NOT NULL,           -- 所属知识库
    file_name      VARCHAR(255) NOT NULL,
    file_type      VARCHAR(20)  NOT NULL,            -- MARKDOWN/PDF/HTML/TICKET
    storage_path   VARCHAR(500) NOT NULL,
    content_hash   VARCHAR(64)  NOT NULL,            -- SHA-256，变更检测
    status         VARCHAR(20)  NOT NULL DEFAULT 'DRAFT', -- DRAFT/REVIEW/PUBLISHED/DEPRECATED
    version        VARCHAR(50),
    effective_from DATE,
    expires_at     DATE,
    uploader_id    VARCHAR(36)  NOT NULL,
    reviewer_id    VARCHAR(36),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Chunk 表（pgvector 扩展）
CREATE TABLE knowledge_chunk (
    id                  VARCHAR(36)   PRIMARY KEY,
    doc_id              VARCHAR(36)   NOT NULL REFERENCES knowledge_doc(id),
    kb_id               VARCHAR(36)   NOT NULL,           -- 冗余自 knowledge_doc.kb_id，避免 JOIN
    doc_status          VARCHAR(20)   NOT NULL DEFAULT 'PUBLISHED', -- 冗余自 knowledge_doc.status，随文档状态同步更新
    parent_chunk_id     VARCHAR(36),                      -- Parent-Child 双层存储
    breadcrumb          TEXT,                             -- 面包屑上下文
    content             TEXT          NOT NULL,
    content_vector      vector(1024)  NOT NULL,           -- BGE-M3 输出维度
    token_count         INTEGER       NOT NULL,
    retrieval_weight    DECIMAL(3,2)  NOT NULL DEFAULT 1.0,
    feedback_downvotes  INTEGER       NOT NULL DEFAULT 0,
    hypothetical_questions  JSONB,                        -- 假设性问题列表
    metadata            JSONB,                            -- source_url/doc_version 等
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- 向量检索索引（HNSW，查询快，内存换速度）
CREATE INDEX idx_chunk_vector ON knowledge_chunk
    USING hnsw (content_vector vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- 单表检索辅助索引：kb_id + doc_status + retrieval_weight（覆盖 WHERE 条件，避免全表扫描）
CREATE INDEX idx_chunk_kb_status ON knowledge_chunk (kb_id, doc_status, retrieval_weight)
    WHERE doc_status = 'PUBLISHED' AND retrieval_weight > 0;
```

### 8.2 conversation-service 核心表

```sql
-- 会话表
CREATE TABLE conversation_session (
    id              VARCHAR(36)  PRIMARY KEY,
    user_id         VARCHAR(36)  NOT NULL,
    status          VARCHAR(30)  NOT NULL DEFAULT 'AI_HANDLING',
    escalation_reason VARCHAR(100),
    agent_id        VARCHAR(36),                     -- 接入的座席 ID
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    closed_at       TIMESTAMPTZ
);

-- 消息表
CREATE TABLE conversation_message (
    id          VARCHAR(36)   PRIMARY KEY,
    session_id  VARCHAR(36)   NOT NULL REFERENCES conversation_session(id),
    role        VARCHAR(10)   NOT NULL,              -- USER/ASSISTANT/AGENT
    content     TEXT          NOT NULL,
    model       VARCHAR(50),                         -- 使用的模型
    trace_id    VARCHAR(36),                         -- 关联 RagTrace
    agent_id    VARCHAR(36),                         -- 座席 ID（role=AGENT 时填写）
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- 用户长期记忆表
CREATE TABLE user_memory (
    id          VARCHAR(36)   PRIMARY KEY,
    user_id     VARCHAR(36)   NOT NULL,
    summary     TEXT          NOT NULL,              -- LLM 生成的会话摘要
    session_id  VARCHAR(36)   NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_user_memory_uid ON user_memory(user_id, created_at DESC);

-- RAG 追踪表（可观测性）
CREATE TABLE rag_trace (
    id                    VARCHAR(36)   PRIMARY KEY,
    session_id            VARCHAR(36)   NOT NULL,
    user_id               VARCHAR(36),
    original_query        TEXT          NOT NULL,
    rewritten_query       TEXT,
    selected_model        VARCHAR(50),
    prompt_tokens         INTEGER,
    completion_tokens     INTEGER,
    faithfulness_score    DECIMAL(4,3),
    answer_relevancy_score DECIMAL(4,3),
    escalated_to_human    BOOLEAN       NOT NULL DEFAULT FALSE,
    query_rewrite_ms      INTEGER,
    retrieval_ms          INTEGER,
    rerank_ms             INTEGER,
    generation_ms         INTEGER,
    total_ms              INTEGER,
    user_feedback         SMALLINT,                  -- 1=满意 0=不满意
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_rag_trace_session ON rag_trace(session_id, created_at DESC);
CREATE INDEX idx_rag_trace_created ON rag_trace(created_at DESC);
```

---

## 9 API 设计

### 9.1 knowledge-service API

```
# 文档管理
POST   /api/knowledge/docs/upload          上传文档（返回 202 + docId，异步处理）
GET    /api/knowledge/docs/{docId}/status  查询摄取进度
PUT    /api/knowledge/docs/{docId}/review  审核通过/退回
DELETE /api/knowledge/docs/{docId}         下线文档（物理删除 chunk）

# 知识库管理
POST   /api/knowledge/kbs                  创建知识库
GET    /api/knowledge/kbs                  列表查询
DELETE /api/knowledge/kbs/{kbId}           删除知识库

# 检索接口（供 conversation-service 内部调用，非对外）
POST   /internal/knowledge/search          混合检索（BM25 + 向量）
POST   /internal/knowledge/rerank          重排序
```

**上传响应示例：**
```json
// POST /api/knowledge/docs/upload → 202 Accepted
{
  "docId": "doc_abc123",
  "status": "PENDING",
  "message": "文档已接收，正在后台处理，可通过 docId 查询进度"
}

// GET /api/knowledge/docs/doc_abc123/status → 200
{
  "docId": "doc_abc123",
  "status": "PUBLISHED",
  "chunkCount": 42,
  "processedAt": "2026-06-25T10:05:32Z"
}
```

### 9.2 conversation-service API

```
# 对话接口
POST   /api/conversation/sessions          创建会话（返回 sessionId）
GET    /api/conversation/chat/stream       SSE 流式对话（query 参数传递）
POST   /api/conversation/chat/feedback     提交反馈（点赞/点踩）

# 转接接口
POST   /api/conversation/sessions/{id}/escalate   主动请求转人工
GET    /api/conversation/sessions/{id}/context    获取会话上下文（座席使用）

# 管理接口
GET    /api/conversation/sessions          分页查询会话列表
GET    /api/conversation/sessions/{id}     查询会话详情
```

**流式对话接口：**
```
GET /api/conversation/chat/stream?sessionId=xxx&query=你们产品怎么收费

响应（text/event-stream）：
data: {"token": "产品"}
data: {"token": "定价"}
data: {"token": "分为"}
...
data: {"done": true, "traceId": "trace_xyz", "sources": [{"docId": "doc_001", "breadcrumb": "产品手册 > 定价"}]}
```

**反馈接口：**
```json
// POST /api/conversation/chat/feedback
{
  "traceId": "trace_xyz",
  "feedback": 0,          // 1=满意 0=不满意
  "comment": "回答不准确"  // 可选
}
```

### 9.3 OpenAPI 规范（SpringDoc 配置与接口文档）

#### SpringDoc 统一配置

两个服务各自配置 SpringDoc，风格与平台其他服务一致（已用 `springdoc-openapi-starter-webmvc-ui:2.6.0`）：

```java
package com.aidevplatform.knowledge.interfaces.config;

/**
 * OpenAPI 文档配置。
 * 访问地址：http://localhost:{port}/swagger-ui/index.html
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI knowledgeOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("智能客服 - 知识库服务 API")
                .description("文档摄取、知识库管理、向量检索接口文档")
                .version("v1.0.0")
                .contact(new Contact()
                    .name("AI Platform Team")
                    .email("ai-platform@example.com"))
            )
            .addSecurityItem(new SecurityRequirement().addList("Sa-Token"))
            .components(new Components()
                .addSecuritySchemes("Sa-Token", new SecurityScheme()
                    .type(SecurityScheme.Type.APIKEY)
                    .in(SecurityScheme.In.HEADER)
                    .name("satoken")));
    }
}
```

#### Controller 接口注解规范

所有对外 Controller 必须加 `@Tag` + `@Operation`，参数加 `@Parameter`，响应加 `@ApiResponse`：

```java
@Slf4j
@RestController
@RequestMapping("/api/knowledge/docs")
@RequiredArgsConstructor
@Tag(name = "文档管理", description = "知识库文档上传、状态查询、审核、下线")
public class KnowledgeDocController {

    private final DocIngestAppService  docIngestAppService;
    private final DocVoAssembler       docVoAssembler;

    @Operation(
        summary = "上传文档",
        description = "异步处理，立即返回 202 Accepted 和 docId，通过状态查询接口轮询进度"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "文档已接收，处理中"),
        @ApiResponse(responseCode = "400", description = "文件格式不支持或参数错误"),
        @ApiResponse(responseCode = "401", description = "未登录")
    })
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocUploadVO> upload(
            @Parameter(description = "文件（支持 PDF/MD/HTML/DOCX）", required = true)
            @RequestPart("file") MultipartFile file,
            @Parameter(description = "所属知识库 ID", required = true)
            @RequestParam String kbId) {
        DocUploadVO vo = docIngestAppService.submit(file, kbId);
        return ResponseEntity.accepted().body(vo);
    }

    @Operation(summary = "查询文档摄取进度")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "查询成功"),
        @ApiResponse(responseCode = "404", description = "文档不存在")
    })
    @GetMapping("/{docId}/status")
    public DocStatusVO status(
            @Parameter(description = "文档 ID", required = true)
            @PathVariable String docId) {
        return docVoAssembler.toStatusVO(docIngestAppService.getStatus(docId));
    }

    @Operation(summary = "审核文档", description = "REVIEW 状态的文档通过审核后进入 PUBLISHED 状态")
    @PutMapping("/{docId}/review")
    public void review(
            @PathVariable String docId,
            @RequestBody @Valid DocReviewRequest request) {
        docIngestAppService.review(docId, request.isApproved(), request.getRejectReason());
    }

    @Operation(summary = "下线文档", description = "物理删除该文档所有 chunk，不可恢复")
    @ApiResponse(responseCode = "204", description = "下线成功")
    @DeleteMapping("/{docId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void offline(@PathVariable String docId) {
        docIngestAppService.offline(docId);
    }
}
```

```java
@Slf4j
@RestController
@RequestMapping("/api/conversation")
@RequiredArgsConstructor
@Tag(name = "智能对话", description = "会话管理、流式对话、反馈、人工转接")
public class ConversationController {

    private final ConversationAppService conversationAppService;

    @Operation(summary = "创建会话")
    @ApiResponse(responseCode = "201", description = "会话创建成功")
    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionVO createSession(@RequestBody @Valid CreateSessionRequest request) {
        return conversationAppService.createSession(request.getUserId(), request.getKbId());
    }

    @Operation(
        summary = "流式对话（SSE）",
        description = "返回 text/event-stream，每条 data 为 token 片段，最后一条含 done=true 和溯源信息"
    )
    @ApiResponse(responseCode = "200", description = "流式输出",
        content = @Content(mediaType = "text/event-stream",
            schema = @Schema(example = "data: {\"token\": \"产品定价\"}\n\ndata: {\"done\": true, \"traceId\": \"xxx\"}")))
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @Parameter(description = "会话 ID", required = true) @RequestParam String sessionId,
            @Parameter(description = "用户问题", required = true) @RequestParam String query) {
        return conversationAppService.streamChat(sessionId, query);
    }

    @Operation(summary = "提交对话反馈（点赞/点踩）")
    @PostMapping("/chat/feedback")
    public void feedback(@RequestBody @Valid ChatFeedbackRequest request) {
        conversationAppService.saveFeedback(request.getTraceId(),
            request.getFeedback(), request.getComment());
    }

    @Operation(summary = "主动请求转人工")
    @PostMapping("/sessions/{id}/escalate")
    public void escalate(@PathVariable String id) {
        conversationAppService.escalate(id);
    }
}
```

#### 统一响应体规范

所有接口响应统一包装为 `Result<T>`，与平台其他服务保持一致：

```java
/**
 * 统一响应体。复用 common-web 模块已有实现，不重复定义。
 * 流式接口（SSE）直接返回 SseEmitter，不包装 Result。
 */
@Data
@Builder
public class Result<T> {
    /** 业务状态码：0=成功，非 0=业务错误 */
    private int    code;
    /** 提示信息 */
    private String message;
    /** 响应数据 */
    private T      data;

    public static <T> Result<T> ok(T data) {
        return Result.<T>builder().code(0).message("success").data(data).build();
    }

    public static <T> Result<T> fail(int code, String message) {
        return Result.<T>builder().code(code).message(message).build();
    }
}
```

#### 全局异常处理

```java
/**
 * 全局异常处理器，统一将异常转换为 Result 响应。
 * 与平台其他服务规范一致。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：返回业务错误码，不打印堆栈 */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBusiness(BusinessException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    /** 参数校验失败：返回 400，提取首条错误信息 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
            .orElse("参数校验失败");
        return Result.fail(400, msg);
    }

    /** 未知异常：返回 500，隐藏实现细节，打印完整堆栈供排查 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleUnknown(Exception e) {
        log.error("系统异常", e);
        return Result.fail(500, "系统繁忙，请稍后再试");
    }
}
```

#### OpenAPI 文档访问地址

| 服务 | 文档地址 | 说明 |
|---|---|---|
| knowledge-service | `http://localhost:8081/swagger-ui/index.html` | 文档管理、知识库 API |
| conversation-service | `http://localhost:8082/swagger-ui/index.html` | 对话、座席 API |
| 内部检索接口 | 不暴露 Swagger（`/internal/**` 路径排除） | 仅服务间调用 |

内部接口排除出 Swagger 文档：

```java
// application.yml
springdoc:
  paths-to-exclude:
    - /internal/**
  api-docs:
    path: /v3/api-docs
  swagger-ui:
    path: /swagger-ui/index.html
    tags-sorter: alpha
    operations-sorter: alpha
```

### 9.4 服务间调用设计（knowledge-sdk）

#### 调用关系

```
conversation-service
    ↓  knowledge-sdk（OkHttp + AK/SK HMAC 签名）
knowledge-service（/internal/knowledge/**）
    ← ApiKeyAuthFilter 鉴权（与平台其他服务完全一致）
```

`knowledge-sdk` 完全复用 `common-sdk` 的 `BaseClient` + `AkSkSigningInterceptor`，
无需引入 Feign / Spring Cloud，与平台其他 SDK（`code-sdk`、`pipeline-sdk`）风格统一。

#### knowledge-sdk 模块结构

```
ai-knowledge/
└── knowledge-sdk/
    └── src/main/java/com/aidevplatform/sdk/knowledge/
        ├── KnowledgeClient.java        # SDK 主入口，extends BaseClient
        ├── dto/
        │   ├── SearchRequest.java      # 检索请求
        │   ├── SearchResponse.java     # 检索响应
        │   ├── RerankRequest.java      # 重排序请求
        │   └── ChunkHitDTO.java        # chunk 命中结果
        └── KnowledgeClientAutoConfig.java  # Spring Boot 自动装配（可选）
```

#### KnowledgeClient 实现

```java
package com.aidevplatform.sdk.knowledge;

/**
 * knowledge-service SDK 客户端。
 * 供 conversation-service 调用内部检索接口，复用 common-sdk 的 AK/SK 签名和重试机制。
 *
 * <p>使用示例：
 * <pre>
 * KnowledgeClient client = KnowledgeClient.builder()
 *     .baseUrl("http://knowledge-service:8081")
 *     .accessKey(ak, sk)
 *     .build();
 * SearchResponse result = client.search(SearchRequest.of("如何退款", "kb_001", 5));
 * </pre>
 */
public class KnowledgeClient extends BaseClient {

    private KnowledgeClient(ClientConfig config) {
        super(config);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 混合检索（BM25 + 向量双路召回 + RRF 融合）。
     * 对应内部接口：POST /internal/knowledge/search
     *
     * @param request 检索请求（含查询文本、知识库 ID、topK）
     * @return 按相关性降序排列的 chunk 命中列表
     */
    public SearchResponse search(SearchRequest request) {
        return post("/internal/knowledge/search", request, SearchResponse.class);
    }

    /**
     * 重排序（BGE-Reranker cross-encoder 精排）。
     * 对应内部接口：POST /internal/knowledge/rerank
     *
     * @param request 重排请求（含查询文本和待排序 chunk 列表）
     * @return 精排后的 chunk 列表
     */
    public SearchResponse rerank(RerankRequest request) {
        return post("/internal/knowledge/rerank", request, SearchResponse.class);
    }

    public static class Builder {
        private final ClientConfig.Builder configBuilder = ClientConfig.builder();

        public Builder baseUrl(String url)          { configBuilder.baseUrl(url);        return this; }
        public Builder accessKey(String ak, String sk) { configBuilder.accessKey(ak, sk); return this; }
        public Builder connectTimeout(Duration d)   { configBuilder.connectTimeout(d);   return this; }
        public Builder readTimeout(Duration d)      { configBuilder.readTimeout(d);       return this; }
        public Builder maxRetries(int n)            { configBuilder.maxRetries(n);        return this; }

        public KnowledgeClient build() {
            return new KnowledgeClient(configBuilder.build());
        }
    }
}
```

#### DTO 定义

```java
/** 检索请求 */
@Data
@Builder
public class SearchRequest {
    /** 检索查询文本（已经过改写） */
    private String query;
    /** 目标知识库 ID */
    private String kbId;
    /** 返回 top-K 条结果 */
    private int    topK;

    public static SearchRequest of(String query, String kbId, int topK) {
        return SearchRequest.builder().query(query).kbId(kbId).topK(topK).build();
    }
}

/** chunk 命中结果 */
@Data
@Builder
public class ChunkHitDTO {
    private String chunkId;
    private String docId;
    private String content;
    private String breadcrumb;   // 面包屑：产品手册 > 定价
    private String parentChunkId;
    private double score;        // RRF 分或 rerank 分
    private String source;       // VECTOR / BM25 / RERANK
}

/** 检索/重排序响应 */
@Data
@Builder
public class SearchResponse {
    private List<ChunkHitDTO> hits;
    private int               totalFound;
    private long              retrievalMs; // 检索耗时（毫秒）
}
```

#### knowledge-service 内部接口（InternalKnowledgeController）

```java
package com.aidevplatform.knowledge.interfaces.rest;

/**
 * 供 conversation-service 调用的内部检索接口。
 * 路径前缀 /internal/**，由 ApiKeyAuthFilter 统一鉴权（与平台规范一致）。
 * 不暴露 Swagger 文档（application.yml 中已配置 paths-to-exclude: /internal/**）。
 */
@Slf4j
@RestController
@RequestMapping("/internal/knowledge")
@RequiredArgsConstructor
// 不加 @Tag，internal 接口不对外暴露 OpenAPI 文档
public class InternalKnowledgeController {

    private final KnowledgeSearchAppService searchAppService;

    /**
     * 混合检索入口。
     * conversation-service 通过 KnowledgeClient.search() 调用此接口。
     */
    @PostMapping("/search")
    public Result<SearchResponse> search(@RequestBody @Valid SearchRequest request) {
        List<ChunkHit> hits = searchAppService.hybridSearch(
            request.getQuery(), request.getKbId(), request.getTopK());
        return Result.ok(SearchResponse.fromDomain(hits));
    }

    /**
     * 重排序入口（BGE-Reranker）。
     */
    @PostMapping("/rerank")
    public Result<SearchResponse> rerank(@RequestBody @Valid RerankRequest request) {
        List<ChunkHit> reranked = searchAppService.rerank(
            request.getQuery(), request.getChunks());
        return Result.ok(SearchResponse.fromDomain(reranked));
    }
}
```

#### Spring Boot 自动装配（conversation-service 侧配置）

```java
/**
 * KnowledgeClient Spring Bean 自动配置。
 * conversation-service 引入 knowledge-sdk 依赖后，
 * 只需在 application.yml 配置 ak/sk 和地址即可自动注入。
 */
@Configuration
@ConditionalOnClass(KnowledgeClient.class)
public class KnowledgeClientAutoConfig {

    @Bean
    @ConditionalOnMissingBean
    public KnowledgeClient knowledgeClient(
            @Value("${knowledge.client.base-url}") String baseUrl,
            @Value("${knowledge.client.access-key}") String ak,
            @Value("${knowledge.client.secret-key}") String sk,
            @Value("${knowledge.client.connect-timeout-ms:3000}") long connectTimeoutMs,
            @Value("${knowledge.client.read-timeout-ms:10000}") long readTimeoutMs) {
        return KnowledgeClient.builder()
            .baseUrl(baseUrl)
            .accessKey(ak, sk)
            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
            .readTimeout(Duration.ofMillis(readTimeoutMs))
            .maxRetries(3)
            .build();
    }
}
```

```yaml
# conversation-service application.yml
knowledge:
  client:
    base-url: http://knowledge-service:8081
    access-key: ${KNOWLEDGE_ACCESS_KEY}   # 从环境变量注入，不硬编码
    secret-key: ${KNOWLEDGE_SECRET_KEY}
    connect-timeout-ms: 3000
    read-timeout-ms: 10000
```

#### 熔断降级（Resilience4j 包装）

`KnowledgeClient` 调用在 `KnowledgeChunkRepositoryImpl` 内包裹熔断器，
knowledge-service 不可用时降级返回空列表，conversation-service 走纯 BM25 兜底：

```java
// infrastructure 层 Repository 实现内
@Override
public List<ChunkHit> vectorSearch(float[] queryVector, int topK, String kbId) {
    return circuitBreaker.executeSupplier(() -> {
        SearchResponse resp = knowledgeClient.search(
            SearchRequest.of(VectorUtils.toStr(queryVector), kbId, topK));
        return resp.getHits().stream().map(ChunkHitAssembler::toDomain).toList();
    });
    // 熔断触发时 Resilience4j 抛 CallNotPermittedException
    // 由上层 HybridRetriever 捕获并降级为空列表
}
```

---

## 10 开源方案对比

### 10.1 主流开源智能客服/RAG 框架对比

| 框架 | 定位 | 优势 | 劣势 | 与本系统关系 |
|---|---|---|---|---|
| **Dify** | 低代码 LLM 应用平台 | 开箱即用，可视化流程编排，RAG 完整 | Python 技术栈，定制性弱，黑盒难调优 | 不采用，Java 平台需深度定制 |
| **RAGFlow** | 专注 RAG 的文档智能平台 | 文档解析能力强（DeepDoc 引擎） | 独立部署，与现有平台集成成本高 | 参考其文档解析策略，不直接引入 |
| **Qanything** | 网易本地知识库问答 | 中文效果好，支持私有化 | Python 技术栈，维护活跃度一般 | 参考其混合检索实现 |
| **FastGPT** | 知识库问答 SaaS/私有化 | 用户体验好，工作流功能完善 | 功能封闭，无法嵌入已有服务 | 不采用 |
| **LangChain4j** | Java LLM 应用框架 | **Java 原生**，Spring Boot 集成，活跃维护 | 抽象层有学习成本 | **核心依赖**，用于 LLM 调用和 RAG 编排 |
| **Spring AI** | Spring 官方 AI 框架 | 与 Spring Boot 深度集成，API 统一 | 相对新，生态不如 LangChain4j 成熟 | **备选**，可与 LangChain4j 互补 |

### 10.2 选型结论

本系统基于 Java 17 + Spring Boot 3 自建，核心依赖如下：

```xml
<!-- LLM 调用与 RAG 编排 -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-spring-boot-starter</artifactId>
    <version>0.36.2</version>
</dependency>

<!-- pgvector 支持 -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-pgvector</artifactId>
    <version>0.36.2</version>
</dependency>

<!-- PDF 解析 -->
<dependency>
    <groupId>org.apache.pdfbox</groupId>
    <artifactId>pdfbox</artifactId>
    <version>3.0.3</version>
</dependency>

<!-- HTML 解析 -->
<dependency>
    <groupId>org.jsoup</groupId>
    <artifactId>jsoup</artifactId>
    <version>1.18.3</version>
</dependency>

<!-- Word 解析 -->
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
    <version>5.3.0</version>
</dependency>

<!-- 熔断器 -->
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
    <version>2.2.0</version>
</dependency>
```

**不引入 LangChain（Python）的原因**：平台技术栈统一 Java，引入 Python 服务意味着增加一套部署运维体系，维护成本不可接受。LangChain4j 提供了等价能力。

---

## 11 非功能性要求

### 11.1 性能要求

| 接口 | P50 | P99 | 备注 |
|---|---|---|---|
| 简单 FAQ（Embedding 缓存命中） | < 150ms | < 400ms | 小模型直接回答 |
| 普通 RAG 对话 | < 600ms | < 2s | 流式输出，首 token < 500ms |
| 复杂推理/投诉 | < 1s | < 4s | 大模型，流式输出 |
| 文档上传接口 | < 50ms | < 200ms | 仅写 Redis Streams，立即返回 |
| 文档摄取吞吐 | — | — | ≥ 100 文档/分钟（3 Worker） |

### 11.2 可用性要求

- 对话服务（conversation-service）可用性 ≥ 99.9%（月停机 < 44 分钟）
- 知识库服务（knowledge-service）可用性 ≥ 99.5%（异步服务，短暂不可用不影响在线对话）
- 主模型不可用时，熔断降级到备用模型，降级期间功能降级但服务不中断
- Redis 集群单节点故障，自动故障转移，对话服务无感知

### 11.3 可扩展性要求

- 向量存储抽象层（`VectorStore` 接口）隔离 pgvector，chunk 量 > 500 万时可平滑迁移 Milvus/Qdrant
- LLM 客户端抽象层（`LLMClient` 接口）隔离各大模型 SDK，新增模型只需实现接口，无需修改路由逻辑
- 文档解析器插件化（`DocumentParser` 接口），新增格式只需新增实现类

### 11.4 安全要求

- 所有接口走 Sa-Token 鉴权，与平台统一
- 工单历史数据入库前必须脱敏（手机号、身份证、银行卡号正则替换为 `***`）
- LLM 调用使用 API Key，存储于配置中心（不硬编码），定期轮换
- Prompt 注入防护：用户输入经过长度截断（max 2000 字符）和敏感词过滤后再拼入 Prompt

### 11.5 数据保留策略

| 数据类型 | 保留周期 | 说明 |
|---|---|---|
| RagTrace | 90 天 | 用于质量分析和告警，定期归档 |
| 会话消息 | 180 天 | 用户可申请导出 |
| 长期记忆摘要 | 用户注销时清除 | 最多保留最近 20 条摘要 |
| 被下线的 chunk | 立即物理删除 | 不软删，避免新旧混存 |
| 黄金测试集跑分记录 | 永久保留 | 质量趋势分析 |

### 11.6 版本兼容策略

- 采用颠覆式破坏性更改策略，API 版本通过 URL 路径区分（`/api/v1/`、`/api/v2/`）
- 旧版本 API 不向后兼容，升级时提供迁移文档
- 数据库变更通过 Flyway 管理，迁移脚本不可修改只可追加

---

## 12 人工座席功能设计

### 12.1 座席端核心功能

座席端承接 AI 无法处理的会话，需要以下能力：

| 功能 | 说明 |
|---|---|
| 上线 / 下线 | 控制是否接受新会话，下线后在途会话继续直到关闭 |
| 查看等待队列 | 按等待时长排序，显示转接原因、用户信息 |
| 接入会话 | 原子操作防止多个座席抢同一个会话 |
| 实时消息收发 | WebSocket 双向通信，低延迟 |
| 查看 AI 上下文 | AI 对话历史 + 最后一次检索到的 chunk，辅助座席快速理解问题 |
| 转交其他座席 | 座席不在或不擅长时可转交，上下文一并转移 |
| 关闭会话 | 标记 RESOLVED，异步触发长期记忆写入 |
| 并发数限制 | 单座席最多同时接 N 个会话（默认 5），超出后不再分配新会话 |

### 12.2 座席队列管理

等待队列用 Redis Sorted Set 实现，score = 进入队列时间戳，越早优先级越高：

**Mapper 层（会话消息持久化）：**

```java
@Mapper
public interface ConversationMessageMapper extends BaseMapper<ConversationMessageEntity> {

@Mapper
public interface ConversationMessageMapper extends BaseMapper<ConversationMessageEntity> {
    // 按 session_id 查询消息历史，简单条件，直接用 LambdaQueryWrapper，无需自定义 SQL
}
```

**Service 层（listBySession 改用 LambdaQueryWrapper）：**

```java
@Override
public List<ConversationMessageEntity> listBySession(String sessionId) {
    // 简单单表查询，LambdaQueryWrapper 即可，不需要 @Select 方法
    return list(new LambdaQueryWrapper<ConversationMessageEntity>()
        .eq(ConversationMessageEntity::getSessionId, sessionId)
        .orderByAsc(ConversationMessageEntity::getCreatedAt)
    );
}
```

**Service 层（MyBatis-Plus 快速实现方式，严格 DDD 请参考第 13 章 Repository 模式）：**

```java
// ⚠️ 同 3.4 节说明：IService 接口渗透到 application 层，严格 DDD 团队改用 Repository 接口
public interface ConversationMessageService extends IService<ConversationMessageEntity> {
    /** 保存一条消息（role 使用枚举，禁止传裸字符串） */
    void saveMessage(String sessionId, MessageRole role, String content, String agentId);
    /** 查询会话完整消息历史 */
    List<ConversationMessageEntity> listBySession(String sessionId);
}

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationMessageServiceImpl
        extends ServiceImpl<ConversationMessageMapper, ConversationMessageEntity>
        implements ConversationMessageService {

    @Override
    public void saveMessage(String sessionId, MessageRole role,
                             String content, String agentId) {
        ConversationMessageEntity entity = ConversationMessageEntity.builder()
            .id(IdGenerator.nextId())
            .sessionId(sessionId)
            .role(role.name())
            .content(content)
            .agentId(agentId)
            .createdAt(Instant.now())
            .build();
        save(entity);
    }

    @Override
    public List<ConversationMessageEntity> listBySession(String sessionId) {
        // 简单条件直接用 LambdaQueryWrapper，不需要 Mapper 自定义方法
        return list(new LambdaQueryWrapper<ConversationMessageEntity>()
            .eq(ConversationMessageEntity::getSessionId, sessionId)
            .orderByAsc(ConversationMessageEntity::getCreatedAt)
        );
    }
}
```

**AgentQueueService（规范重写）：**

```java
/**
 * 座席队列管理服务。
 * 等待队列：Redis Sorted Set，score = 转接时间戳，越早越优先。
 * 分配映射：Redis Hash，sessionId → agentId。
 * 并发计数：Redis Hash，agentId → 当前接入会话数。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentQueueService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final String QUEUE_KEY       = "agent:queue:waiting";
    private static final String ASSIGNED_KEY    = "agent:queue:assigned";
    private static final String CONCURRENT_KEY  = "agent:concurrent";
    private static final int    MAX_CONCURRENT  = 5;

    /**
     * Lua 原子脚本：并发检查 + 出队 + 分配 + 计数，四步全部原子执行，彻底消除竞态条件。
     * KEYS[1] = agent:queue:waiting（Sorted Set，等待队列）
     * KEYS[2] = agent:queue:assigned（Hash，sessionId→agentId）
     * KEYS[3] = agent:concurrent（Hash，agentId→当前接入数）
     * ARGV[1] = agentId
     * ARGV[2] = MAX_CONCURRENT（并发上限）
     * 返回值：分配到的 sessionId，无可用会话或已达上限时返回 nil
     */
    private static final RedisScript<String> ACCEPT_SCRIPT = RedisScript.of("""
        local current = tonumber(redis.call('HGET', KEYS[3], ARGV[1]) or '0')
        if current >= tonumber(ARGV[2]) then return nil end
        local items = redis.call('ZPOPMIN', KEYS[1], 1)
        if #items == 0 then return nil end
        local sessionId = items[1]
        redis.call('HSET', KEYS[2], sessionId, ARGV[1])
        redis.call('HINCRBY', KEYS[3], ARGV[1], 1)
        return sessionId
        """, String.class);

    /**
     * 用户转接时将 sessionId 加入等待队列。
     *
     * @param sessionId 会话 ID
     */
    public void enqueue(String sessionId) {
        redisTemplate.opsForZSet().add(QUEUE_KEY, sessionId, System.currentTimeMillis());
        log.info("会话已进入等待队列，sessionId={}", sessionId);
    }

    /**
     * 座席接入：原子出队并记录分配关系。
     * 并发检查、出队、分配、计数四步均在 Lua 脚本内原子执行，消除竞态条件。
     *
     * @param agentId 座席 ID
     * @return 分配到的 sessionId，无等待会话或座席满载时返回 empty
     */
    public Optional<String> accept(String agentId) {
        String sessionId = redisTemplate.execute(
            ACCEPT_SCRIPT,
            List.of(QUEUE_KEY, ASSIGNED_KEY, CONCURRENT_KEY),
            agentId, String.valueOf(MAX_CONCURRENT)
        );
        if (StringUtils.hasText(sessionId)) {
            log.info("座席接入会话，agentId={}，sessionId={}", agentId, sessionId);
        } else {
            log.debug("座席无法接入：队列为空或已达并发上限，agentId={}", agentId);
        }
        return Optional.ofNullable(sessionId);
    }

    /**
     * 会话关闭时释放座席容量。
     *
     * @param sessionId 会话 ID
     * @param agentId   座席 ID
     */
    /**
     * 会话关闭时原子释放座席容量。
     * 使用 Lua 脚本保证 HDEL + HINCRBY 原子执行，
     * 避免 JVM 崩溃发生在两条命令之间导致计数器永久泄漏。
     */
    private static final RedisScript<Void> RELEASE_SCRIPT = RedisScript.of("""
        redis.call('HDEL', KEYS[1], ARGV[1])
        redis.call('HINCRBY', KEYS[2], ARGV[2], -1)
        """, Void.class);

    public void release(String sessionId, String agentId) {
        redisTemplate.execute(
            RELEASE_SCRIPT,
            List.of(ASSIGNED_KEY, CONCURRENT_KEY),
            sessionId, agentId
        );
        log.info("座席释放会话，agentId={}，sessionId={}", agentId, sessionId);
    }

    /**
     * 查询当前等待队列，按等待时长升序。
     *
     * @return sessionId 列表
     */
    public List<String> listWaiting() {
        Set<String> members = redisTemplate.opsForZSet().range(QUEUE_KEY, 0, -1);
        return members == null ? Collections.emptyList() : new ArrayList<>(members);
    }
    // canAcceptMore() 已删除：并发检查已内聚到 ACCEPT_SCRIPT Lua 脚本，此处不再需要独立方法
}

### 12.3 座席端 API

```
# 座席状态
POST /api/agent/online                   座席上线（开始接受新会话）
POST /api/agent/offline                  座席下线（不再分配新会话）

# 队列
GET  /api/agent/queue                    查看等待队列（按等待时长排序）
POST /api/agent/sessions/accept          接入会话（原子出队）

# 会话操作
GET  /api/agent/sessions/{id}/context   查看完整上下文（AI历史+检索chunk）
POST /api/agent/sessions/{id}/close     关闭会话（RESOLVED）
POST /api/agent/sessions/{id}/transfer  转交其他座席

# 实时消息（WebSocket）
WS   /ws/agent/sessions/{id}            双向消息通道
```

### 12.4 实时消息通道（WebSocket）

座席与用户的消息都通过 WebSocket 实时推送，用户侧走 SSE（单向），座席侧走 WebSocket（双向）：

**AgentWebSocketHandler（规范重写）：**

```java
/**
 * 座席 WebSocket 消息处理器。
 * 职责：接收座席消息 → 委托 Service 持久化 → 推送给用户 SSE。
 * 遵循阿里规范：Handler 不做业务逻辑，全部委托 Service 层。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private final AgentMessageService          agentMessageService;
    private final UserSseRegistry              userSseRegistry;

    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage raw) {
        AgentMessageRequest request;
        try {
            request = JSON.parseObject(raw.getPayload(), AgentMessageRequest.class);
        } catch (Exception e) {
            log.warn("座席消息反序列化失败，payload={}", raw.getPayload(), e);
            return;
        }
        // 业务逻辑全部委托 Service，Handler 保持薄
        agentMessageService.handleAgentMessage(request, userSseRegistry);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        String agentId = (String) ws.getAttributes().get("agentId");
        // 连接断开不自动关闭会话，等待座席重连或手动转交
        log.warn("座席 WebSocket 连接断开，agentId={}，closeStatus={}", agentId, status);
    }
}

/**
 * 座席消息业务 Service，与 Handler 分离，便于单测。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentMessageService {

    private final ConversationMessageService conversationMessageService;

    /**
     * 处理座席发送的消息：持久化 + 推送用户。
     *
     * @param request        座席消息请求
     * @param userSseRegistry 用户 SSE 连接注册表
     */
    public void handleAgentMessage(AgentMessageRequest request,
                                    UserSseRegistry userSseRegistry) {
        // 1. 持久化座席消息（走 IService，不直接碰 Mapper）
        conversationMessageService.saveMessage(
            request.getSessionId(),
            MessageRole.AGENT,
            request.getContent(),
            request.getAgentId()
        );
        // 2. 实时推送给用户侧 SSE 连接
        userSseRegistry.push(request.getSessionId(),
            SseEvent.ofAgent(request.getContent()));
        log.info("座席消息已处理，agentId={}，sessionId={}",
            request.getAgentId(), request.getSessionId());
    }
}
```

### 12.5 座席与 AI 协作流

座席接入后可以看到 AI 侧的完整上下文，包括最后一次检索到的知识库内容，辅助快速定位问题：

```
座席接入
    ↓
GET /api/agent/sessions/{id}/context
    返回：
    {
      "conversationHistory": [...],       // 完整 AI 对话历史
      "filledSlots": {...},               // 已收集的槽位信息
      "escalationReason": "置信度低",    // 转接原因
      "lastRetrievedChunks": [           // AI 最后一次检索结果（供参考）
        { "content": "...", "breadcrumb": "产品手册 > 定价", "score": 0.82 }
      ],
      "waitingSince": "2026-06-25T10:05:00Z"
    }
    ↓
座席基于上下文直接回复，无需用户重复描述
    ↓
座席关闭会话
    ↓
异步触发长期记忆摘要生成（含座席备注）
```

---

## 13 DDD 架构规范与工具类封装

### 13.1 分层包结构

与平台现有模块（`code-service`、`pipeline-service`）保持完全一致，**补充 `domain/service/` 领域服务层**：

```
com.aidevplatform.knowledge
├── interfaces                         # 接口层：Controller、内部 RPC
│   └── rest
│       ├── KnowledgeDocController.java
│       ├── InternalSearchController.java
│       └── vo/                        # VO：对外请求/响应结构
│
├── application                        # 应用层：编排用例，零业务规则
│   └── service
│       ├── DocIngestAppService.java    # 摄取用例（解析→分块→Embed→存库）
│       ├── DocUpdateAppService.java    # 原子替换用例
│       └── KnowledgeSearchAppService.java # 混合检索用例
│
├── domain                             # 域层：业务规则核心，无任何框架依赖
│   ├── model
│   │   ├── KnowledgeDoc.java          # 领域实体（无 @TableName）
│   │   ├── KnowledgeChunk.java
│   │   ├── ChunkQuality.java          # 值对象：chunk 质量分
│   │   └── DocStatus.java             # 领域枚举
│   ├── service                        # ★ 领域服务：不属于单个 Entity 的业务规则
│   │   └── ChunkQualityDomainService.java  # chunk 质量评分规则
│   └── repository                     # Repository 接口（域层定义）
│       ├── KnowledgeDocRepository.java
│       └── KnowledgeChunkRepository.java
│
└── infrastructure                     # 基础设施层：DB/MQ/外部 API
    ├── persistence/mapper/entity/repository/assembler/
    ├── mq/
    └── vector/

com.aidevplatform.conversation
├── interfaces/
├── application
│   └── service
│       ├── ConversationAppService.java   # RAG 对话用例编排
│       └── SessionAppService.java        # 会话生命周期用例
│
├── domain
│   ├── model
│   │   ├── ConversationSession.java
│   │   ├── ConversationMessage.java
│   │   ├── Intent.java
│   │   ├── SlotTemplate.java             # 值对象：意图→槽位模板
│   │   └── ConversationStatus.java
│   ├── service                           # ★ 领域服务
│   │   ├── SlotFillingDomainService.java     # 槽位填充业务规则
│   │   ├── IntentComplexityDomainService.java # 意图复杂度评分规则
│   │   ├── RrfDomainService.java             # RRF 融合排序算法
│   │   └── FaithfulnessDomainService.java    # 忠实度判断规则
│   └── repository/
│
└── infrastructure/
    └── agent/AgentQueueAdapter.java
```

### 13.2 各层职责边界与依赖规则

```
依赖方向（单向，禁止反向依赖）：
interfaces → application → domain ← infrastructure
                               ↑
                          domain/service
                        （被 application 调用）

层级          职责                                    禁止事项
──────────────────────────────────────────────────────────────────
interfaces   参数校验、VO 转换、HTTP/WS 适配         调用 Mapper / Domain Service ❌
application  用例编排（调用 Domain Service + Repository）  含业务规则 ❌
domain/model 实体、值对象、领域枚举                  依赖 Spring/MyBatis ❌
domain/service 跨实体的业务规则、核心算法             依赖 Repository ❌（不做持久化）
domain/repository 持久化接口定义                    依赖 MyBatis / JdbcTemplate ❌
infrastructure 技术实现（DB/MQ/向量库/外部 API）      直接被 application 层调用 ❌
```

**Application Service vs Domain Service 判断标准：**

```
问：这段逻辑是"做什么"还是"怎么做"？

做什么（Application Service）= 流程编排：
  先摄取文档，再向量化，再存库，最后通知 → DocIngestAppService

怎么做（Domain Service）= 业务规则：
  chunk 质量怎么算合格？                 → ChunkQualityDomainService
  RRF 算法如何融合多路检索结果？          → RrfDomainService
  槽位是否已齐全、缺哪个、怎么追问？      → SlotFillingDomainService
  意图复杂度怎么打分？                   → IntentComplexityDomainService
  回答忠实度如何判断？                   → FaithfulnessDomainService
```

### 13.3 Domain Service 详细设计

Domain Service 放在 `domain/service/` 包，**无任何 Spring 注解以外的框架依赖**，
只操作域内类型（领域实体、值对象、枚举），不直接访问数据库。

#### ChunkQualityDomainService — chunk 质量评分

```java
package com.aidevplatform.knowledge.domain.service;

/**
 * Chunk 质量领域服务。
 * 判断 chunk 是否具备入库资格，核心业务规则不依赖任何框架。
 * 评分维度：语义完整性（coherence）、信息密度（density）、自包含性（selfContained）。
 */
@Service
public class ChunkQualityDomainService {

    /** 各维度最低通过阈值 */
    private static final double MIN_COHERENCE     = 0.6;
    private static final double MIN_DENSITY       = 0.5;
    private static final double MIN_SELF_CONTAINED = 0.5;

    /**
     * 判断 chunk 是否通过质量门控。
     * 低于阈值的 chunk 不入库，调用方负责合并到邻近 chunk。
     *
     * @param chunk 待检测 chunk（领域对象，非 Entity）
     * @return true = 通过，false = 不通过
     */
    public boolean passQualityGate(KnowledgeChunk chunk) {
        ChunkQuality quality = chunk.getQuality();
        return quality.coherence()     >= MIN_COHERENCE
            && quality.density()       >= MIN_DENSITY
            && quality.selfContained() >= MIN_SELF_CONTAINED;
    }

    /**
     * 过滤列表，返回通过质量门控的 chunk。
     *
     * @param chunks 待过滤列表
     * @return 合格 chunk 列表
     */
    public List<KnowledgeChunk> filter(List<KnowledgeChunk> chunks) {
        return chunks.stream()
            .filter(this::passQualityGate)
            .toList();
    }
}
```

#### SlotFillingDomainService — 槽位填充业务规则

```java
package com.aidevplatform.conversation.domain.service;

/**
 * 槽位填充领域服务。
 * 职责：判断槽位是否齐全、找出缺失的必填槽、生成自然语言追问。
 * 纯业务规则，不访问数据库，不依赖框架。
 */
@Service
public class SlotFillingDomainService {

    /**
     * 检查意图所需槽位是否全部满足。
     *
     * @param intent      当前意图
     * @param filledSlots 已填充的槽位（来自 ConversationContext）
     * @return SlotCheckResult：完整（可执行）或不完整（含追问话术）
     */
    public SlotCheckResult check(Intent intent, Map<String, String> filledSlots) {
        List<SlotDefinition> required = intent.getSlotTemplate().getRequiredSlots();
        List<SlotDefinition> missing = required.stream()
            .filter(slot -> !filledSlots.containsKey(slot.getName()))
            .toList();

        if (missing.isEmpty()) {
            return SlotCheckResult.complete(filledSlots);
        }
        // 只追问第一个缺失槽，避免用户一次面对多个问题
        String prompt = buildNaturalPrompt(missing.get(0));
        return SlotCheckResult.incomplete(filledSlots, missing, prompt);
    }

    /**
     * 生成自然语言追问，而非机器人式的"请输入XXX："。
     */
    private String buildNaturalPrompt(SlotDefinition missingSlot) {
        return switch (missingSlot.getName()) {
            case "order_id"     -> "好的，请问您的订单号是多少？";
            case "phone_tail"   -> "方便提供一下您的手机号后四位吗？";
            case "refund_reason"-> "能简单说一下您申请退款的原因吗？";
            case "product_name" -> "请问您想咨询哪款产品呢？";
            default -> "还需要了解一下您的" + missingSlot.getLabel() + "，方便告知吗？";
        };
    }
}
```

#### RrfDomainService — RRF 融合算法

```java
package com.aidevplatform.conversation.domain.service;

/**
 * RRF（Reciprocal Rank Fusion）领域服务。
 * 核心检索算法，属于业务规则范畴，放置于 domain/service 层。
 * score(d) = Σ 1 / (K + rank_i(d))，K=60 为业界通用默认值。
 */
@Service
public class RrfDomainService {

    private static final int DEFAULT_K = 60;

    /**
     * 融合多路检索结果，返回按相关性降序排列的统一列表。
     *
     * @param topK   最终保留条数
     * @param lists  多路检索结果（可变参数，支持两路、三路）
     * @return RRF 融合结果
     */
    @SafeVarargs
    public final List<ChunkHit> fuse(int topK, List<ChunkHit>... lists) {
        Map<String, Double> scoreMap  = new LinkedHashMap<>();
        Map<String, ChunkHit> chunkMap = new LinkedHashMap<>();

        for (List<ChunkHit> list : lists) {
            for (int i = 0; i < list.size(); i++) {
                ChunkHit hit = list.get(i);
                scoreMap.merge(hit.getChunkId(), 1.0 / (DEFAULT_K + i + 1), Double::sum);
                chunkMap.putIfAbsent(hit.getChunkId(), hit);
            }
        }

        return scoreMap.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(topK)
            .map(e -> chunkMap.get(e.getKey()).withScore(e.getValue()))
            .toList();
    }
}
```

#### IntentComplexityDomainService — 意图复杂度评分

```java
package com.aidevplatform.conversation.domain.service;

/**
 * 意图复杂度领域服务。
 * 决定当前问题应路由到哪个档位的模型，属于业务决策规则。
 * 优先走规则判断（零 LLM 开销），无法判断时降级到 MEDIUM。
 */
@Service
public class IntentComplexityDomainService {

    /**
     * 根据用户问题和召回 chunk 评估复杂度。
     *
     * @param query  用户问题（已改写）
     * @param chunks RRF 融合后的 top chunk 列表
     * @return 复杂度等级
     */
    public IntentComplexity evaluate(String query, List<ChunkHit> chunks) {
        // 规则 1：涉及代码/技术报错 → CODE
        if (containsCodeKeywords(query)) {
            return IntentComplexity.CODE;
        }
        // 规则 2：涉及投诉/纠纷/法律 → COMPLEX
        if (containsComplaintKeywords(query)) {
            return IntentComplexity.COMPLEX;
        }
        // 规则 3：需要综合多文档 → MEDIUM
        if (chunks.size() >= 4) {
            return IntentComplexity.MEDIUM;
        }
        // 规则 4：高置信度单跳命中 → SIMPLE
        if (query.length() < 20 && !chunks.isEmpty()
                && chunks.get(0).getScore() > 0.85) {
            return IntentComplexity.SIMPLE;
        }
        return IntentComplexity.MEDIUM;
    }

    private boolean containsCodeKeywords(String query) {
        return query.matches(".*(报错|异常|exception|error|stack.*trace|NPE|404|500).*");
    }

    private boolean containsComplaintKeywords(String query) {
        return query.matches(".*(投诉|维权|欺诈|骗|律师|仲裁|赔偿|纠纷).*");
    }
}
```

#### FaithfulnessDomainService — 忠实度判断规则

```java
package com.aidevplatform.conversation.domain.service;

/**
 * 忠实度领域服务。
 * 判断 AI 生成的回答是否有检索内容支撑，防止 LLM 幻觉。
 * 评分策略：原子声明逐条验证，能从 chunk 中找到支撑的比例即为忠实度分。
 */
@Service
@RequiredArgsConstructor
public class FaithfulnessDomainService {

    /** 忠实度低于此阈值则触发告警或转人工 */
    public static final double LOW_FAITHFULNESS_THRESHOLD = 0.7;

    private final LlmClient llmClient; // 基础设施层接口，通过 DI 注入

    /**
     * 异步计算忠实度分（不阻塞主链路）。
     *
     * @param answer 生成的回答
     * @param chunks 检索到的 chunk 列表
     * @return CompletableFuture<Double> 0~1 之间的忠实度分
     */
    public CompletableFuture<Double> scoreAsync(String answer, List<ChunkHit> chunks) {
        return CompletableFuture.supplyAsync(() -> score(answer, chunks));
    }

    /**
     * 判断忠实度是否低于告警阈值。
     */
    public boolean isLowFaithfulness(double score) {
        return score < LOW_FAITHFULNESS_THRESHOLD;
    }

    private double score(String answer, List<ChunkHit> chunks) {
        String context = chunks.stream()
            .map(ChunkHit::getContent)
            .collect(Collectors.joining("\n---\n"));
        // 调用轻量 LLM 逐条验证原子声明（Qwen-turbo，低成本）
        return llmClient.faithfulnessScore(answer, context);
    }
}
```

#### Application Service 调用 Domain Service 示例

```java
// ✅ 正确：Application Service 编排流程，业务规则委托 Domain Service
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationAppService {

    // Application 层依赖 Domain Service（业务规则）和 Repository（持久化）
    private final SlotFillingDomainService      slotFillingDomainService;
    private final IntentComplexityDomainService complexityDomainService;
    private final RrfDomainService              rrfDomainService;
    private final FaithfulnessDomainService     faithfulnessDomainService;
    private final KnowledgeChunkRepository      chunkRepository;
    private final EmbeddingPort                 embeddingPort;   // 基础设施 Port
    private final LlmRouterPort                 llmRouterPort;   // 基础设施 Port

    public Flux<String> chat(String sessionId, String query, ConversationContext ctx) {
        // 1. 槽位检查（业务规则 → Domain Service）
        SlotCheckResult slotResult = slotFillingDomainService.check(
            ctx.getCurrentIntent(), ctx.getFilledSlots());
        if (!slotResult.isComplete()) {
            return Flux.just(slotResult.getPrompt());
        }

        // 2. 混合检索（基础设施 → Repository）
        float[] vector = embeddingPort.encode(query);
        List<ChunkHit> vectorHits = chunkRepository.vectorSearch(vector, 20, ctx.getKbId());
        List<ChunkHit> bm25Hits   = chunkRepository.bm25Search(query, 20, ctx.getKbId());

        // 3. RRF 融合（业务规则 → Domain Service）
        List<ChunkHit> fused = rrfDomainService.fuse(5, vectorHits, bm25Hits);

        // 4. 意图复杂度评分（业务规则 → Domain Service）
        IntentComplexity complexity = complexityDomainService.evaluate(query, fused);

        // 5. 路由生成（基础设施 → Port）
        // 用 StringBuffer（线程安全）收集完整回答，供忠实度异步校验使用。
        // ⚠️ 不能用 StringBuilder：Flux 链中 publishOn/subscribeOn 可能导致
        //    doOnNext（追加）和 doOnComplete（读取）在不同线程执行，引发数据竞争。
        StringBuffer answerCollector = new StringBuffer();
        Flux<String> stream = llmRouterPort.streamChat(query, fused, ctx, complexity);

        // 6. 忠实度校验（业务规则 → Domain Service，doOnNext 收集 token，doOnComplete 触发校验）
        return stream
            .doOnNext(answerCollector::append)
            .doOnComplete(() ->
                faithfulnessDomainService.scoreAsync(answerCollector.toString(), fused)
                    .thenAccept(score -> {
                        if (faithfulnessDomainService.isLowFaithfulness(score)) {
                            log.warn("忠实度低于阈值，sessionId={}，score={}", sessionId, score);
                        }
                    })
            );
    }
}
```

### 13.4 Repository 模式（违规修正）

**当前违规（已在本文档代码中存在）：**

```java
// ❌ 违规：Application Service 接口暴露 MyBatis-Plus 框架
public interface KnowledgeDocService extends IService<KnowledgeDocEntity> { }

// ❌ 违规：extends ServiceImpl 出现在 application 层
public class KnowledgeDocServiceImpl extends ServiceImpl<...> { }

// ❌ 违规：Mapper 直接注入到 Application Service
public class HybridRetriever {
    private final KnowledgeChunkMapper chunkMapper; // 基础设施泄漏到应用层
}
```

**修正后的正确写法：**

```java
// ✅ 域层：Repository 接口，无任何框架依赖
package com.aidevplatform.knowledge.domain.repository;

public interface KnowledgeDocRepository {
    /** 保存或更新文档 */
    void save(KnowledgeDoc doc);
    /** 按 ID 查询 */
    Optional<KnowledgeDoc> findById(String docId);
    /** 查询已过期文档 */
    List<KnowledgeDoc> findExpired(LocalDate today);
    /** 批量更新状态 */
    void updateStatusBatch(List<String> docIds, DocStatus status);
}

public interface KnowledgeChunkRepository {
    void saveAll(List<KnowledgeChunk> chunks);
    void deleteByDocId(String docId);
    List<ChunkHit> vectorSearch(float[] queryVector, int topK, String kbId);
    List<ChunkHit> bm25Search(String query, int topK, String kbId);
}
```

```java
// ✅ 基础设施层：Repository 实现，MyBatis-Plus 细节全部封装在此
package com.aidevplatform.knowledge.infrastructure.persistence.repository;

@Repository
@RequiredArgsConstructor
public class KnowledgeDocRepositoryImpl implements KnowledgeDocRepository {

    // Mapper 只在基础设施层内部使用，不向上层暴露
    private final KnowledgeDocMapper     docMapper;
    private final KnowledgeDocAssembler  assembler;

    @Override
    public void save(KnowledgeDoc doc) {
        KnowledgeDocEntity entity = assembler.toEntity(doc);
        docMapper.insert(entity);
    }

    @Override
    public Optional<KnowledgeDoc> findById(String docId) {
        return Optional.ofNullable(docMapper.selectById(docId))
            .map(assembler::toDomain);
    }

    @Override
    public List<KnowledgeDoc> findExpired(LocalDate today) {
        // selectExpiredDocs 已删除，改用 LambdaQueryWrapper 单表查询
        return docMapper.selectList(
            new LambdaQueryWrapper<KnowledgeDocEntity>()
                .lt(KnowledgeDocEntity::getExpiresAt, today)
                .ne(KnowledgeDocEntity::getStatus, DocStatus.DEPRECATED.name())
        ).stream().map(assembler::toDomain).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatusBatch(List<String> docIds, DocStatus status) {
        if (CollectionUtils.isEmpty(docIds)) {
            return;
        }
        docMapper.update(null,
            new LambdaUpdateWrapper<KnowledgeDocEntity>()
                .in(KnowledgeDocEntity::getId, docIds)
                .set(KnowledgeDocEntity::getStatus, status.name())
        );
    }
}
```

### 13.5 Application Service 规范（违规修正）

Application Service 只做**用例编排**，不含业务规则，不依赖任何框架。

```java
// ✅ 正确：Application Service 依赖 Repository 接口（域层），不依赖 Mapper
package com.aidevplatform.knowledge.application.service;

/**
 * 文档更新应用服务。
 * 职责：编排"原子替换文档"用例，协调 Repository 完成状态切换。
 * 不含业务规则（业务规则在 domain 层），不含持久化细节（在 infrastructure 层）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocUpdateAppService {

    // ✅ 依赖域层 Repository 接口，不直接注入 Mapper 或 ServiceImpl
    private final KnowledgeDocRepository   docRepository;
    private final KnowledgeChunkRepository chunkRepository;

    /**
     * 原子切换文档：新 chunk 就绪后下线旧文档，同一事务保证一致性。
     */
    @Transactional(rollbackFor = Exception.class)
    public void atomicSwap(String oldDocId, String newDocId) {
        docRepository.updateStatusBatch(List.of(oldDocId), DocStatus.DEPRECATED);
        docRepository.updateStatusBatch(List.of(newDocId), DocStatus.PUBLISHED);
        chunkRepository.deleteByDocId(oldDocId);
        log.info("文档原子替换完成，旧 docId={} 已下线", oldDocId);
    }
}

/**
 * 知识库检索应用服务。
 * 职责：编排"混合检索"用例，不直接操作 Mapper。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeSearchAppService {

    // ✅ 依赖 Repository 接口，不直接注入 KnowledgeChunkMapper
    private final KnowledgeChunkRepository chunkRepository;
    private final EmbeddingService         embeddingService;

    public List<ChunkHit> hybridSearch(String query, String kbId, int topK) {
        float[] queryVector = embeddingService.encode(query);

        CompletableFuture<List<ChunkHit>> vectorFuture = CompletableFuture.supplyAsync(
            () -> chunkRepository.vectorSearch(queryVector, topK * 2, kbId));
        CompletableFuture<List<ChunkHit>> bm25Future = CompletableFuture.supplyAsync(
            () -> chunkRepository.bm25Search(query, topK * 2, kbId));

        return RrfUtils.fuse(topK, vectorFuture.join(), bm25Future.join());
    }
}
```

### 13.6 Assembler 转换层

Assembler 负责 **Entity（基础设施）↔ Domain Object（域层）↔ VO（接口层）** 三层对象互转，
禁止在 Application Service 或 Controller 内做手工字段赋值。

```java
// ✅ 基础设施层内的 Assembler：Entity ↔ 领域对象
package com.aidevplatform.knowledge.infrastructure.persistence.assembler;

@Component
public class KnowledgeDocAssembler {

    /**
     * Entity → 领域对象（向上传递给 Application / Domain 层）
     */
    public KnowledgeDoc toDomain(KnowledgeDocEntity entity) {
        if (entity == null) {
            return null;
        }
        return KnowledgeDoc.builder()
            .id(entity.getId())
            .kbId(entity.getKbId())
            .fileName(entity.getFileName())
            .fileType(DocFileType.valueOf(entity.getFileType()))
            .contentHash(entity.getContentHash())
            .status(DocStatus.valueOf(entity.getStatus()))
            .version(entity.getVersion())
            .effectiveFrom(entity.getEffectiveFrom())
            .expiresAt(entity.getExpiresAt())
            .createdAt(entity.getCreatedAt())
            .build();
    }

    /**
     * 领域对象 → Entity（向下持久化到数据库）
     */
    public KnowledgeDocEntity toEntity(KnowledgeDoc domain) {
        if (domain == null) {
            return null;
        }
        return KnowledgeDocEntity.builder()
            .id(domain.getId())
            .kbId(domain.getKbId())
            .fileName(domain.getFileName())
            .fileType(domain.getFileType().name())
            .contentHash(domain.getContentHash())
            .status(domain.getStatus().name())
            .version(domain.getVersion())
            .effectiveFrom(domain.getEffectiveFrom())
            .expiresAt(domain.getExpiresAt())
            .build();
    }
}

// ✅ 接口层内的 Assembler：VO ↔ 领域对象（Controller 内调用）
package com.aidevplatform.knowledge.interfaces.rest.assembler;

@Component
public class DocVoAssembler {

    public DocStatusVO toVO(KnowledgeDoc domain) {
        return DocStatusVO.builder()
            .docId(domain.getId())
            .status(domain.getStatus().name())
            .chunkCount(domain.getChunkCount())
            .build();
    }
}
```

### 13.7 工具类统一封装

所有工具类放在 `common-core` 模块（平台已有），按职责分类，禁止在业务代码内散落工具方法。

#### RrfUtils — RRF 融合算法

```java
package com.aidevplatform.common.core.util;

/**
 * Reciprocal Rank Fusion 工具类。
 * 将多路检索结果按 RRF 算法融合排序。
 * score(d) = Σ 1 / (K + rank_i(d))，K=60 为业界通用默认值。
 */
public final class RrfUtils {

    /** RRF 平滑参数，值越大越平滑，业界通用 60 */
    private static final int DEFAULT_K = 60;

    private RrfUtils() {}

    /**
     * 融合多路检索结果。
     *
     * @param topK   最终保留条数
     * @param lists  多路检索结果，支持可变参数（两路、三路均可）
     * @return RRF 融合后按相关性降序排列的列表
     */
    @SafeVarargs
    public static List<ChunkHit> fuse(int topK, List<ChunkHit>... lists) {
        return fuse(DEFAULT_K, topK, lists);
    }

    @SafeVarargs
    public static List<ChunkHit> fuse(int k, int topK, List<ChunkHit>... lists) {
        Map<String, Double> scoreMap = new LinkedHashMap<>();
        Map<String, ChunkHit> chunkMap = new LinkedHashMap<>();

        for (List<ChunkHit> list : lists) {
            for (int i = 0; i < list.size(); i++) {
                ChunkHit hit = list.get(i);
                scoreMap.merge(hit.getChunkId(), 1.0 / (k + i + 1), Double::sum);
                chunkMap.putIfAbsent(hit.getChunkId(), hit);
            }
        }

        return scoreMap.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(topK)
            .map(e -> chunkMap.get(e.getKey()).withScore(e.getValue()))
            .toList();
    }
}
```

#### VectorUtils — 向量格式转换

```java
package com.aidevplatform.common.core.util;

/**
 * 向量工具类，统一处理 float[] 与 pgvector 字符串格式互转。
 * pgvector 要求格式：[0.1,0.2,0.3]
 */
public final class VectorUtils {

    private VectorUtils() {}

    /**
     * float[] → pgvector 字符串，如 [0.1,0.2,0.3]。
     *
     * @param vector embedding 向量，不能为 null 或空数组
     * @return pgvector 可接受的字符串
     * @throws IllegalArgumentException vector 为 null 或空时抛出
     */
    public static String toStr(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("向量不能为空");
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) {
                sb.append(",");
            }
        }
        return sb.append("]").toString();
    }

    /**
     * pgvector 字符串 → float[]。
     *
     * @param vectorStr [0.1,0.2,0.3] 格式字符串
     * @return float 数组
     */
    public static float[] fromStr(String vectorStr) {
        if (!StringUtils.hasText(vectorStr)) {
            throw new IllegalArgumentException("向量字符串不能为空");
        }
        String trimmed = vectorStr.trim();
        // 去除首尾方括号
        if (trimmed.startsWith("[")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        String[] parts = trimmed.split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Float.parseFloat(parts[i].trim());
        }
        return result;
    }
}
```

#### TokenUtils — Token 计数估算

```java
package com.aidevplatform.common.core.util;

/**
 * Token 计数工具类。
 * 精确计数需调用 Tokenizer API，此处提供本地快速估算（误差 ±10%）。
 * 规则：中文按 1 字 ≈ 1 token，英文按 4 字符 ≈ 1 token。
 */
public final class TokenUtils {

    private TokenUtils() {}

    /**
     * 快速估算文本的 token 数量（本地，无 API 调用开销）。
     *
     * @param text 待估算文本
     * @return 估算 token 数
     */
    public static int estimate(String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        int chineseCount  = 0;
        int otherCount    = 0;
        for (char c : text.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') {
                chineseCount++;
            } else {
                otherCount++;
            }
        }
        // 中文 1 字 ≈ 1 token；英文/符号 4 字符 ≈ 1 token
        return chineseCount + (otherCount / 4) + 1;
    }

    /**
     * 判断文本是否超过 token 上限。
     *
     * @param text     待检测文本
     * @param maxToken token 上限
     * @return 超出则返回 true
     */
    public static boolean exceeds(String text, int maxToken) {
        return estimate(text) > maxToken;
    }
}
```

#### SensitiveDataUtils — 隐私脱敏

```java
package com.aidevplatform.common.core.util;

/**
 * 隐私数据脱敏工具类。
 * 用于历史工单入库前的数据清洗，防止 PII 进入向量库。
 */
public final class SensitiveDataUtils {

    /** 手机号：保留前 3 位和后 4 位 */
    private static final Pattern PHONE_PATTERN =
        Pattern.compile("(1[3-9]\\d)\\d{4}(\\d{4})");

    /** 身份证号：保留前 6 位和后 4 位 */
    private static final Pattern ID_CARD_PATTERN =
        Pattern.compile("(\\d{6})\\d{8}(\\d{4}[Xx])");

    /** 银行卡号：保留后 4 位 */
    private static final Pattern BANK_CARD_PATTERN =
        Pattern.compile("\\d{12,19}(\\d{4})");

    private SensitiveDataUtils() {}

    /**
     * 对文本中的 PII 数据进行脱敏处理。
     *
     * @param text 原始文本
     * @return 脱敏后的文本
     */
    public static String desensitize(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        String result = PHONE_PATTERN.matcher(text).replaceAll("$1****$2");
        result = ID_CARD_PATTERN.matcher(result).replaceAll("$1********$2");
        result = BANK_CARD_PATTERN.matcher(result).replaceAll("************$1");
        return result;
    }
}
```

#### RedisCacheHelper — 通用缓存操作封装

> 放置于 `common-web` 模块，需要 Spring RedisTemplate，不能放无 Spring 依赖的 `common-core`。

```java
package com.aidevplatform.common.web.redis;

/**
 * 通用 Redis 缓存操作封装。
 * 统一处理序列化、TTL、key 规范，业务代码只关心存取逻辑。
 *
 * <p>使用规范：
 * <ul>
 *   <li>key 必须带业务前缀，格式：{module}:{type}:{id}，例如 emb:query:md5xxx</li>
 *   <li>所有缓存必须设置 TTL，禁止永不过期</li>
 *   <li>获取不到返回 null，由调用方决定是否回源，此类不做回源逻辑</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisCacheHelper {

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 获取缓存值并反序列化为指定类型。
     *
     * @param key   缓存 key（格式：{module}:{type}:{id}）
     * @param clazz 目标类型
     * @param <T>   目标类型泛型
     * @return 缓存值，不存在或已过期时返回 null
     */
    public <T> T get(String key, Class<T> clazz) {
        Object value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return null;
        }
        return clazz.cast(value);
    }

    /**
     * 写入缓存，带 TTL。
     *
     * @param key   缓存 key
     * @param value 缓存值（需可序列化）
     * @param ttl   存活时间，不得传 null 或零
     * @throws IllegalArgumentException ttl 为 null 或 isZero/isNegative 时抛出
     */
    public void set(String key, Object value, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("TTL 必须大于零，key=" + key);
        }
        redisTemplate.opsForValue().set(key, value, ttl);
    }

    /**
     * 删除缓存 key。
     *
     * @param key 缓存 key
     * @return true=删除成功，false=key 不存在
     */
    public boolean delete(String key) {
        Boolean result = redisTemplate.delete(key);
        return Boolean.TRUE.equals(result);
    }

    /**
     * 判断 key 是否存在。
     *
     * @param key 缓存 key
     * @return true=存在且未过期
     */
    public boolean exists(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /**
     * 获取缓存；不存在时执行 loader 回源并写入缓存（Cache-Aside 模式）。
     *
     * @param key    缓存 key
     * @param clazz  目标类型
     * @param ttl    缓存存活时间
     * @param loader 回源函数，不得返回 null（返回 null 则不缓存）
     * @param <T>    目标类型泛型
     * @return 缓存值或回源值
     */
    public <T> T getOrLoad(String key, Class<T> clazz, Duration ttl,
                            java.util.function.Supplier<T> loader) {
        // 先读缓存（快路径）
        T cached = get(key, clazz);
        if (cached != null) {
            return cached;
        }

        // 缓存未命中，用分布式锁防止缓存击穿（多个请求同时回源）
        // 锁 key：{原 key}:lock，TTL 为回源超时上限（10s）
        String lockKey = key + ":lock";
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, "1", Duration.ofSeconds(10));

        if (Boolean.TRUE.equals(locked)) {
            // 获取到锁：执行回源并写缓存
            try {
                T value = loader.get();
                if (value != null) {
                    set(key, value, ttl);
                }
                return value;
            } finally {
                // 确保锁释放，即使回源异常也不会死锁
                redisTemplate.delete(lockKey);
            }
        } else {
            // 未获取到锁：等待短暂时间后重读缓存（等待持锁方回源完成）
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // 重读一次缓存；若仍为空（持锁方回源很慢），降级直接回源
            T retried = get(key, clazz);
            return retried != null ? retried : loader.get();
        }
    }
}
```

#### RedisStreamHelper — Redis Streams 流操作封装

```java
package com.aidevplatform.common.web.redis;

/**
 * Redis Streams 流操作封装。
 * 统一封装消息发布、消费、XCLAIM 重试、DLQ 转移等操作，
 * 消除业务代码中散落的 redisTemplate.opsForStream() 调用。
 *
 * <p>Redis Streams 核心概念：
 * <ul>
 *   <li>Stream：有序消息日志，消息写入后不可修改</li>
 *   <li>Consumer Group：消费者组，多个消费者协作消费</li>
 *   <li>PEL（Pending Entry List）：已投递但未 ACK 的消息列表</li>
 *   <li>XCLAIM：将 PEL 中空闲超时的消息重新分配给指定消费者（用于故障转移和重试）</li>
 *   <li>delivery-count：Redis 内置的投递次数计数，通过 XPENDING 可读</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisStreamHelper {

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * 向 Stream 发布消息。
     *
     * @param streamKey Stream key
     * @param payload   消息体（Map 格式，key-value 均为字符串）
     * @return 消息 ID（RecordId）
     */
    public RecordId publish(String streamKey, Map<String, String> payload) {
        RecordId id = redisTemplate.opsForStream()
            .add(MapRecord.create(streamKey).withEntries(payload));
        log.debug("消息已发布，streamKey={}，recordId={}", streamKey, id);
        return id;
    }

    /**
     * 拉取新消息（仅返回首次投递的消息，对应 > 游标）。
     * 失败消息不会在此方法中返回，需通过 {@link #claimIdleMessages} 处理。
     *
     * @param streamKey  Stream key
     * @param group      消费者组名
     * @param consumerId 消费者 ID（建议格式：hostname-pid）
     * @param count      单次最多拉取条数
     * @param blockMs    无新消息时阻塞等待毫秒数，0=不阻塞
     * @return 新消息列表，无新消息返回空列表
     */
    public List<MapRecord<String, Object, Object>> readNew(String streamKey, String group,
                                                            String consumerId, int count,
                                                            long blockMs) {
        StreamReadOptions options = blockMs > 0
            ? StreamReadOptions.empty().count(count).block(Duration.ofMillis(blockMs))
            : StreamReadOptions.empty().count(count);

        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
            Consumer.from(group, consumerId),
            options,
            StreamOffset.create(streamKey, ReadOffset.lastConsumed())
        );
        return records == null ? Collections.emptyList() : records;
    }

    /**
     * ACK 确认消息已处理，将其从 PEL 中移除。
     *
     * @param streamKey Stream key
     * @param group     消费者组名
     * @param recordId  消息 ID
     */
    public void acknowledge(String streamKey, String group, RecordId recordId) {
        redisTemplate.opsForStream().acknowledge(streamKey, group, recordId);
    }

    /**
     * 查询 PEL 中空闲超过指定时间的消息并重新认领（XCLAIM）。
     * 用于故障恢复：某 Worker 宕机后，其 pending 消息可被其他 Worker 认领重试。
     *
     * <p>返回认领到的消息，调用方需判断 delivery-count 决定重试还是转 DLQ：
     * <pre>
     * pending.getTotalDeliveryCount() >= maxRetries → 转 DLQ
     * 否则 → 重新处理
     * </pre>
     *
     * @param streamKey  Stream key
     * @param group      消费者组名
     * @param consumerId 认领者 ID
     * @param idleTime   空闲时间阈值，超过此时间才认领
     * @param maxFetch   单次最多认领条数
     * @return 认领到的消息与其 pending 信息，key=消息记录，value=pending 元信息
     */
    public Map<MapRecord<String, Object, Object>, PendingMessage> claimIdleMessages(
            String streamKey, String group, String consumerId,
            Duration idleTime, int maxFetch) {

        // Step 1：查询消费者【组】级别 PEL（所有消费者的 pending 消息），而非当前消费者自己的 PEL。
        // 这是 XCLAIM 的核心场景：Worker-A 宕机后，Worker-B 需要看到 Worker-A 的遗留消息并接管。
        // 注意：opsForStream().pending(streamKey, group, Range, count) 返回组级别 PendingMessages
        PendingMessages pendingMessages = redisTemplate.opsForStream()
            .pending(streamKey, group, Range.unbounded(), maxFetch);

        if (pendingMessages == null || pendingMessages.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<MapRecord<String, Object, Object>, PendingMessage> result = new LinkedHashMap<>();

        for (PendingMessage pending : pendingMessages) {
            // 未到空闲阈值，跳过（消息仍在正常处理中）
            if (pending.getElapsedTimeSinceLastDelivery().compareTo(idleTime) < 0) {
                continue;
            }
            // Step 2：XCLAIM 将消息重新分配给当前消费者（无论原归属哪个 Worker）
            // 只有空闲超时的消息才会被认领，防止抢占正在处理中的消息
            List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream()
                .claim(streamKey, Consumer.from(group, consumerId), idleTime, pending.getId());

            if (!CollectionUtils.isEmpty(claimed)) {
                result.put(claimed.get(0), pending);
            }
        }
        return result;
    }

    /**
     * 将消息内容推入死信队列（Redis List，右侧追加）。
     * DLQ 用于人工排查或补偿处理，不做自动重试。
     *
     * @param dlqKey  死信队列 key（建议格式：{streamKey}:dlq）
     * @param payload 消息内容（JSON 字符串）
     */
    public void pushToDlq(String dlqKey, String payload) {
        redisTemplate.opsForList().rightPush(dlqKey, payload);
        log.warn("消息已转入死信队列，dlqKey={}，payload 长度={}", dlqKey, payload.length());
    }
}
```

#### 工具类归集位置总览

```
common-core/src/main/java/com/aidevplatform/common/core/
├── util/
│   ├── IdGenerator.java        # 雪花 ID 生成（平台已有，直接复用）
│   ├── VectorUtils.java        # float[] ↔ pgvector 字符串互转（新增）
│   ├── RrfUtils.java           # RRF 多路检索融合（新增）
│   ├── TokenUtils.java         # Token 数量快速估算（新增）
│   └── SensitiveDataUtils.java # PII 脱敏（新增）
└── exception/
    └── BusinessException.java  # 业务异常（平台已有，直接复用）

common-web/src/main/java/com/aidevplatform/common/web/
└── redis/
    ├── RedisCacheHelper.java   # 通用缓存操作（get/set/delete/getOrLoad，新增）
    └── RedisStreamHelper.java  # Redis Streams 流操作（publish/readNew/claim/dlq，新增）
```

**Redis 工具使用原则：**
- `RedisCacheHelper` 供所有需要 KV 缓存的场景使用，禁止各模块直接调 `redisTemplate.opsForValue()`
- `RedisStreamHelper` 供所有 Streams 消息场景使用，禁止各模块直接调 `redisTemplate.opsForStream()`
- `AgentQueueService` 的 ZSet/Hash 操作属于业务特定逻辑，Lua 脚本内聚在 Service 内，不抽取到工具类
- 所有 key 命名规范：`{module}:{type}:{id}`，例如 `emb:query:abc123`、`agent:queue:waiting`
```

**使用原则：**
- 工具类一律 `final` + 私有构造器，禁止实例化
- 方法入参需做 null/空检查，违规时抛 `IllegalArgumentException`（非业务异常）
- 禁止在工具类内注入 Spring Bean，保持纯 Java，便于单测

---

## 14 RBAC 权限管理系统设计

### 14.1 权限模型总览

系统采用 **RBAC1（带角色继承的 RBAC）+ 数据权限（Data Scope）** 混合模型：

```
用户(User)
  └── 角色(Role)  ←── 角色继承（预留，当前不启用）
        ├── 菜单权限(Menu)      → 控制前端路由/按钮可见性
        ├── 接口权限(Permission) → 控制后端 API 访问（Sa-Token @SaCheckPermission）
        └── 数据权限(DataScope)  → 控制行级数据可见范围（部门维度）
```

**三层权限体系：**

| 层级 | 控制对象 | 实现方式 | 粒度 |
|---|---|---|---|
| 菜单权限 | 前端路由/按钮显示 | `sys_role_menu` + 动态路由接口 | 菜单/按钮级 |
| 接口权限 | 后端 API 访问 | Sa-Token `@SaCheckPermission` | 接口级 |
| 数据权限 | 查询结果行过滤 | `@DataScope` AOP + 部门树 | 部门级 |

**与现有实现的关系：**
- `sys_user / sys_role / sys_permission / sys_user_role / sys_role_permission` 已存在，**不修改**
- 本章新增：`sys_menu / sys_role_menu / sys_dept / sys_user_dept / sys_role_data_scope`
- Sa-Token `StpInterfaceImpl` 已实现，**无需修改**

### 14.2 数据库表设计（DDL）

#### 完整表关系图

```
sys_dept（部门树）
    ↓ sys_user_dept（用户-部门，支持兼职）
sys_user ──── sys_user_role ──── sys_role ──── sys_role_permission ──── sys_permission
                                     │
                                     ├── sys_role_menu ──── sys_menu（菜单/按钮树）
                                     └── sys_role_data_scope（数据权限范围）
```

#### sys_menu（菜单/按钮表）

```sql
CREATE TABLE auth.sys_menu (
    id              BIGSERIAL     PRIMARY KEY,
    parent_id       BIGINT        NOT NULL DEFAULT 0,     -- 0=根节点
    menu_type       VARCHAR(20)   NOT NULL,               -- DIRECTORY/MENU/BUTTON
    menu_name       VARCHAR(100)  NOT NULL,
    menu_key        VARCHAR(100)  NOT NULL,               -- 路由name或按钮code，全局唯一
    path            VARCHAR(200),                         -- 路由路径（BUTTON类型为null）
    component       VARCHAR(200),                         -- 前端组件路径
    icon            VARCHAR(100),                         -- 如 lucide:message-circle
    sort_order      INT           NOT NULL DEFAULT 0,
    is_visible      BOOLEAN       NOT NULL DEFAULT TRUE,  -- false=隐藏但路由可访问
    is_cache        BOOLEAN       NOT NULL DEFAULT TRUE,  -- keepAlive
    is_external     BOOLEAN       NOT NULL DEFAULT FALSE, -- 外链
    redirect        VARCHAR(200),
    permission_key  VARCHAR(100),                         -- BUTTON类型关联接口权限
    status          VARCHAR(20)   NOT NULL DEFAULT 'active',
    created_by      BIGINT,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP     NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uk_menu_key   ON auth.sys_menu(menu_key);
CREATE INDEX idx_menu_parent      ON auth.sys_menu(parent_id);
```

#### sys_role_menu（角色-菜单关联）

```sql
CREATE TABLE auth.sys_role_menu (
    role_id    BIGINT    NOT NULL,
    menu_id    BIGINT    NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (role_id, menu_id)
);
```

#### sys_dept（部门树）

```sql
CREATE TABLE auth.sys_dept (
    id           BIGSERIAL    PRIMARY KEY,
    parent_id    BIGINT       NOT NULL DEFAULT 0,
    dept_name    VARCHAR(100) NOT NULL,
    dept_code    VARCHAR(50)  NOT NULL,
    -- 祖先ID链，格式：0,1,5，便于子树查询（LIKE '%,deptId,%'）
    ancestor_ids TEXT         NOT NULL DEFAULT '',
    sort_order   INT          NOT NULL DEFAULT 0,
    leader       VARCHAR(50),
    status       VARCHAR(20)  NOT NULL DEFAULT 'active',
    deleted_at   TIMESTAMP,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uk_dept_code ON auth.sys_dept(dept_code) WHERE deleted_at IS NULL;
```

#### sys_user_dept（用户-部门，支持兼职）

```sql
CREATE TABLE auth.sys_user_dept (
    user_id    BIGINT    NOT NULL,
    dept_id    BIGINT    NOT NULL,
    is_primary BOOLEAN   NOT NULL DEFAULT FALSE, -- 主部门标识
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, dept_id)
);
```

#### sys_role_data_scope（角色数据权限范围）

```sql
CREATE TABLE auth.sys_role_data_scope (
    id              BIGSERIAL    PRIMARY KEY,
    role_id         BIGINT       NOT NULL UNIQUE,
    -- ALL=全部 / DEPT_TREE=本部门+子部门 / DEPT_ONLY=仅本部门
    -- CUSTOM_DEPT=自定义部门列表 / SELF=仅本人
    scope_type      VARCHAR(30)  NOT NULL DEFAULT 'SELF',
    custom_dept_ids JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);
```

#### sys_user 补充字段

```sql
-- 主部门冗余字段，避免每次 JOIN，写入时同步更新
ALTER TABLE auth.sys_user
    ADD COLUMN IF NOT EXISTS dept_id   BIGINT,
    ADD COLUMN IF NOT EXISTS dept_name VARCHAR(100);
```

### 14.3 菜单树设计

#### 菜单类型说明

```
DIRECTORY（目录）→ 无实际页面，仅作为菜单分组容器，component 为空
MENU（页面菜单） → 对应一个前端页面，component 指向 Vue 组件路径
BUTTON（按钮）   → 页面内的操作按钮/接口权限，is_visible=false，permission_key 必填
```

#### 智能客服默认菜单树

```
智能客服（DIRECTORY, icon=lucide:bot）
├── 对话（MENU, /customerservice/chat, component=customerservice/chat/index）
├── 知识库（MENU, /customerservice/knowledge）
│   ├── [BUTTON] 上传文档  → permission_key: knowledge:doc:upload
│   ├── [BUTTON] 审核文档  → permission_key: knowledge:doc:review
│   ├── [BUTTON] 下线文档  → permission_key: knowledge:doc:offline
│   └── [BUTTON] 删除文档  → permission_key: knowledge:doc:delete
└── 座席工作台（MENU, /customerservice/agent）
    ├── [BUTTON] 接入会话  → permission_key: agent:session:accept
    ├── [BUTTON] 结束会话  → permission_key: agent:session:close
    └── [BUTTON] 转交会话  → permission_key: agent:session:transfer

系统管理（DIRECTORY, icon=lucide:settings）
├── 用户管理（MENU, /system/user）
│   ├── [BUTTON] 新增/编辑/删除用户
│   ├── [BUTTON] 重置密码
│   └── [BUTTON] 分配角色
├── 角色管理（MENU, /system/role）
│   ├── [BUTTON] 新增/编辑/删除角色
│   └── [BUTTON] 分配菜单
├── 菜单管理（MENU, /system/menu）
└── 部门管理（MENU, /system/dept）
```

#### 前端动态路由接口

```
GET /api/auth/menus/routes
响应：当前登录用户有权访问的菜单树（仅 DIRECTORY + MENU，不含 BUTTON）
前端根据此响应动态注册路由

GET /api/auth/menus/buttons?menuKey=CustomerServiceKnowledge
响应：指定页面下当前用户有权限的按钮 code 列表
前端用于控制按钮 v-if="hasPermission('knowledge:doc:upload')"
```

### 14.4 数据权限（Data Scope）

#### 数据权限范围说明

| scope_type | 含义 | 适用角色 |
|---|---|---|
| `ALL` | 全部数据，不限部门 | 超级管理员 |
| `DEPT_TREE` | 本部门及所有子部门 | 部门负责人 |
| `DEPT_ONLY` | 仅本部门（不含子部门） | 普通管理员 |
| `CUSTOM_DEPT` | 自定义部门列表 | 跨部门协作角色 |
| `SELF` | 仅本人创建的数据 | 普通员工（默认） |

#### 计算规则（多角色取并集，宽松优先）

```
用户拥有多个角色时：
  任一角色为 ALL           → 返回 null（全量，不过滤）
  否则取所有角色部门 ID 的并集 → 能看到任一角色权限范围内的数据
```

#### 使用方式（Service 层）

```java
// 1. Service 方法加 @DataScope 注解
@DataScope(field = "dept_id")
public List<ConversationSession> listSessions(SessionQuery query) {
    // 2. 从 Context 取部门 ID 列表
    List<Long> deptIds = DataScopeContext.getDeptIds();

    // 3. 根据 deptIds 决定查询条件
    LambdaQueryWrapper<ConversationSessionEntity> wrapper =
        new LambdaQueryWrapper<>();

    if (deptIds == null) {
        // null = ALL，不加部门过滤
    } else if (deptIds.isEmpty()) {
        // 空列表 = 无权限，直接返回空
        return Collections.emptyList();
    } else {
        // 按部门过滤
        wrapper.in(ConversationSessionEntity::getDeptId, deptIds);
    }

    return sessionMapper.selectList(wrapper).stream()
        .map(assembler::toDomain).toList();
}
```

#### AOP 切面核心逻辑

```java
@Around("@annotation(DataScope)")
public Object around(ProceedingJoinPoint pjp) throws Throwable {
    try {
        Long userId = StpUtil.getLoginIdAsLong();
        List<Long> roleIds = roleMapper.findRolesByUserId(userId)
            .stream().map(RoleDO::getId).toList();

        List<Long> deptIds = resolveDeptIds(userId, roleIds, anno);
        DataScopeContext.setDeptIds(deptIds);
        return pjp.proceed();
    } finally {
        DataScopeContext.clear(); // 必须清理，防止 ThreadLocal 泄漏
    }
}

private List<Long> resolveDeptIds(Long userId, List<Long> roleIds, DataScope anno) {
    Set<Long> result = new HashSet<>();
    for (Long roleId : roleIds) {
        String scopeType = roleMapper.findScopeTypeByRoleId(roleId);
        switch (scopeType) {
            case "ALL"         -> { if (anno.allowAll()) return null; }  // 全量直接返回
            case "DEPT_TREE"   -> deptMapper.findDeptIdsByUserId(userId)
                                     .forEach(d -> result.addAll(
                                         deptMapper.findSubtreeDeptIds(d)));
            case "DEPT_ONLY"   -> result.addAll(deptMapper.findDeptIdsByUserId(userId));
            case "CUSTOM_DEPT" -> result.addAll(deptMapper.findCustomDeptIdsByRoleId(roleId));
            // SELF：由调用方用 creator_id = userId 过滤，不加部门条件
        }
    }
    return new ArrayList<>(result);
}
```

### 14.5 核心代码实现

#### MenuApplicationService（菜单管理 + 动态路由）

```java
@Service
@RequiredArgsConstructor
public class MenuApplicationService {

    private final MenuMapper menuMapper;

    /**
     * 构建当前用户的动态路由树（供前端 Vben Admin 使用）。
     * 只返回 DIRECTORY + MENU 类型，BUTTON 不在路由中出现。
     */
    public List<Map<String, Object>> buildRouteTree(Long userId) {
        List<MenuDO> menus = menuMapper.findMenusByUserId(userId).stream()
            .filter(m -> !"BUTTON".equals(m.getMenuType()))
            .toList();
        return buildTree(menus, 0L);
    }

    /**
     * 获取指定页面下用户有权限的按钮 code 列表。
     * 前端用于控制按钮级权限（v-if="hasPermission('xxx')"）。
     */
    public List<String> getButtonPermissions(Long userId, String menuKey) {
        return menuMapper.findMenusByUserId(userId).stream()
            .filter(m -> "BUTTON".equals(m.getMenuType())
                && m.getPermissionKey() != null)
            .map(MenuDO::getPermissionKey)
            .toList();
    }

    /**
     * 为角色分配菜单（原子替换）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void assignMenus(Long roleId, List<Long> menuIds) {
        menuMapper.deleteRoleMenus(roleId);
        if (!CollectionUtils.isEmpty(menuIds)) {
            menuIds.forEach(menuId -> menuMapper.insertRoleMenu(roleId, menuId));
        }
    }

    /** 递归构建菜单树，转为 Vben Admin 路由格式 */
    private List<Map<String, Object>> buildTree(List<MenuDO> all, Long parentId) {
        return all.stream()
            .filter(m -> parentId.equals(m.getParentId()))
            .sorted(Comparator.comparing(MenuDO::getSortOrder))
            .map(m -> {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("name", m.getMenuKey());
                node.put("path", m.getPath());
                node.put("component", m.getComponent());
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("title",  m.getMenuName());
                meta.put("icon",   m.getIcon());
                meta.put("keepAlive", Boolean.TRUE.equals(m.getIsCache()));
                meta.put("hideInMenu", !Boolean.TRUE.equals(m.getIsVisible()));
                node.put("meta", meta);
                List<Map<String, Object>> children = buildTree(all, m.getId());
                if (!children.isEmpty()) node.put("children", children);
                return node;
            }).toList();
    }
}
```

#### UserApplicationService 补充（用户 CRUD + 角色分配）

```java
// 在现有 UserApplicationService 中补充以下方法

/** 分配用户角色（原子替换） */
@Transactional(rollbackFor = Exception.class)
public void assignRoles(Long userId, List<Long> roleIds) {
    userMapper.deleteUserRoles(userId);
    roleIds.forEach(roleId -> userMapper.insertUserRole(userId, roleId));
}

/** 分配用户部门（原子替换，同步主部门到 sys_user 冗余字段） */
@Transactional(rollbackFor = Exception.class)
public void assignDepts(Long userId, Long primaryDeptId, List<Long> deptIds) {
    deptMapper.deleteUserDepts(userId);
    deptIds.forEach(deptId ->
        deptMapper.insertUserDept(userId, deptId, deptId.equals(primaryDeptId)));
    // 同步主部门冗余字段
    DeptDO primary = deptMapper.selectById(primaryDeptId);
    if (primary != null) {
        userMapper.update(null, new LambdaUpdateWrapper<UserDO>()
            .eq(UserDO::getId, userId)
            .set(UserDO::getDeptId,   primary.getId())
            .set(UserDO::getDeptName, primary.getDeptName()));
    }
}

/** 重置密码（仅管理员调用，强制下次登录修改） */
@Transactional(rollbackFor = Exception.class)
public void resetPassword(Long userId, String newPassword) {
    String hash = passwordHasher.hash(newPassword);
    userMapper.update(null, new LambdaUpdateWrapper<UserDO>()
        .eq(UserDO::getId, userId)
        .set(UserDO::getPasswordHash,        hash)
        .set(UserDO::getMustChangePassword,  true)
        .set(UserDO::getPasswordChangedAt,   Instant.now()));
}
```

### 14.6 API 设计

#### 用户管理

```
GET    /api/auth/users                    分页查询用户列表（支持关键词/部门/状态过滤）
POST   /api/auth/users                    新增用户
PUT    /api/auth/users/{id}               编辑用户基本信息
DELETE /api/auth/users/{id}               删除用户（软删除）
PUT    /api/auth/users/{id}/roles         分配角色
PUT    /api/auth/users/{id}/depts         分配部门
POST   /api/auth/users/{id}/reset-pwd     重置密码（管理员操作）
PUT    /api/auth/users/{id}/status        启用/禁用用户
```

#### 角色管理

```
GET    /api/auth/roles                    查询角色列表
POST   /api/auth/roles                    新增角色
PUT    /api/auth/roles/{id}               编辑角色
DELETE /api/auth/roles/{id}               删除角色（系统角色禁止删除）
PUT    /api/auth/roles/{id}/permissions   分配接口权限
PUT    /api/auth/roles/{id}/menus         分配菜单权限
PUT    /api/auth/roles/{id}/data-scope    设置数据权限范围
GET    /api/auth/roles/{id}/menus         查询角色已有菜单 ID 列表
```

#### 菜单管理

```
GET    /api/auth/menus/tree               查询完整菜单树（管理员使用）
POST   /api/auth/menus                    新增菜单/按钮
PUT    /api/auth/menus/{id}               编辑菜单
DELETE /api/auth/menus/{id}               删除菜单（有子节点时禁止删除）
GET    /api/auth/menus/routes             获取当前用户动态路由（前端专用）
GET    /api/auth/menus/buttons            获取当前用户按钮权限列表（前端专用）
```

#### 部门管理

```
GET    /api/auth/depts/tree               查询部门树
POST   /api/auth/depts                    新增部门
PUT    /api/auth/depts/{id}               编辑部门
DELETE /api/auth/depts/{id}               删除部门（有员工时禁止删除）
```

### 14.7 与现有 auth-service 的集成关系

#### 现有代码无需修改

| 组件 | 现有实现 | 说明 |
|---|---|---|
| `StpInterfaceImpl` | 已实现接口权限 + 角色查询 | 直接复用，无需修改 |
| `sys_user/role/permission` | 已有完整 CRUD | 在此基础上新增菜单/部门关联 |
| `RoleApplicationService` | 已有权限分配逻辑 | 补充 `assignMenus` 方法 |
| `UserApplicationService` | 已有用户 CRUD | 补充角色/部门分配、重置密码 |

#### 新增内容一览

```
db/migration/
  V5__add_menu_dept_tables.sql    ← 新增 sys_menu/dept/user_dept/role_data_scope 表
  V6__seed_menu_data.sql          ← 智能客服默认菜单 + 管理员数据权限种子数据

infrastructure/persistence/
  menu/MenuDO.java + MenuMapper.java       ← 菜单 Entity + Mapper
  dept/DeptDO.java + DeptMapper.java       ← 部门 Entity + Mapper

domain/datascope/
  DataScope.java           ← 数据权限注解
  DataScopeContext.java    ← ThreadLocal 上下文
  DataScopeAspect.java     ← AOP 切面（核心）

application/service/
  MenuApplicationService.java    ← 菜单管理 + 动态路由构建
  DeptApplicationService.java    ← 部门管理 CRUD

interfaces/rest/
  MenuController.java    ← 菜单 CRUD + 动态路由接口
  DeptController.java    ← 部门管理接口
```

#### RoleMapper 补充方法

```java
// RoleMapper 新增（供 DataScopeAspect 调用）
@Select("""
    SELECT COALESCE(rds.scope_type, 'SELF')
    FROM auth.sys_role r
    LEFT JOIN auth.sys_role_data_scope rds ON rds.role_id = r.id
    WHERE r.id = #{roleId}
    """)
String findScopeTypeByRoleId(@Param("roleId") Long roleId);
```
- 复用 `common-core` 已有的 `IdGenerator`，禁止各模块自行实现
