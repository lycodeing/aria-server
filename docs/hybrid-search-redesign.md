# Aria RAG 混合检索优化技术方案

## 1. 背景与目标

### 1.1 背景

Aria 客服系统的知识库检索模块（`ai-knowledge`）采用混合检索架构，同时运行 BM25 全文检索（PostgreSQL FTS）和向量检索（pgvector 余弦相似度），通过 RRF（Reciprocal Rank Fusion）融合两路结果，可选接入 BGE-Reranker 精排后返回 topK 条供对话服务构建 RAG 上下文。

该架构方向是正确的。但在对当前实现与业界标准做法进行对比分析后，发现存在若干影响检索质量的关键配置与实现问题，在中文客服场景下尤为明显。

### 1.2 目标

| 目标 | 说明 |
|---|---|
| 提升召回率 | 扩大双路召回池，减少因召回数量不足导致的相关内容遗漏 |
| 提升精排效果 | 激活并正确配置 BGE-Reranker，发挥精排对中文语义的优势 |
| 参数解耦 | 将"召回数量"与"最终返回数量 topK"解耦，支持独立调优 |
| 可观测性 | 增加检索链路日志，支持后期数据驱动调优 |

### 1.3 范围

本文覆盖 `ai-knowledge/knowledge-service` 中的检索链路，涉及：

- `KnowledgeSearchAppService.java` — 检索编排
- `RrfUtils.java` — RRF 融合算法
- `KnowledgeChunkMapper.xml` — 向量/全文 SQL
- `application.yml` / `application-prod.yml` — 检索参数配置

不涉及 Embedding 模型替换、分块策略调整、文档解析管道变更。

## 2. 现有架构分析

### 2.1 检索链路全景

```mermaid
flowchart TD
    A([用户查询 query]) --> B[EmbeddingService.encode]
    B --> C["float[] queryVector"]

    C --> D1
    A --> D2

    subgraph parallel ["并行执行 — searchExecutor 线程池"]
        D1["vectorSearch(queryVector, topK×2, kbId)\npgvector 余弦距离"]
        D2["fullTextSearch(query, topK×2, kbId)\nts_rank_cd + plainto_tsquery"]
    end

    D1 -->|"超时 3s 降级→空列表"| E1[vectorHits]
    D2 -->|"超时 3s 降级→空列表"| E2[textHits]

    E1 --> F["RrfUtils.fuse(K=60, topK)\n基于排名融合，输出 topK 条 ID"]
    E2 --> F

    F --> G["重建 ChunkHit 列表\nputIfAbsent，向量结果优先"]
    G --> H{"reranker\nenabled?"}
    H -->|是| I[RerankService.rerank]
    I --> J([返回 topK 条 ChunkHit])
    H -->|否| J
```

### 2.2 关键代码现状

**KnowledgeSearchAppService.hybridSearch（精简版）：**

```java
public List<ChunkHit> hybridSearch(String query, String kbId, int topK) {
    float[] queryVector = embeddingService.encode(query);

    CompletableFuture<List<ChunkHit>> vectorFuture = CompletableFuture.supplyAsync(
        () -> chunkRepository.vectorSearch(queryVector, topK * 2, kbId), searchExecutor);
    CompletableFuture<List<ChunkHit>> textFuture = CompletableFuture.supplyAsync(
        () -> chunkRepository.fullTextSearch(query, topK * 2, kbId), searchExecutor);

    List<ChunkHit> vectorHits = safeGet(vectorFuture, "向量检索", kbId);
    List<ChunkHit> textHits   = safeGet(textFuture,   "全文检索", kbId);

    List<String> vectorIds = vectorHits.stream().map(ChunkHit::getChunkId).toList();
    List<String> textIds   = textHits.stream().map(ChunkHit::getChunkId).toList();

    List<String> fusedIds = RrfUtils.fuse(topK, List.of(vectorIds, textIds));
    // ... 重建 ChunkHit 并可选 rerank
}
```

**RrfUtils.fuseWithK（核心算法）：**

```java
public static List<String> fuseWithK(int topK, int k, List<List<String>> lists) {
    Map<String, Double> scores = new LinkedHashMap<>();
    for (List<String> list : lists) {
        for (int rank = 0; rank < list.size(); rank++) {
            scores.merge(list.get(rank), 1.0 / (k + rank + 1), Double::sum);
        }
    }
    return scores.entrySet().stream()
        .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
        .limit(topK)
        .map(Map.Entry::getKey)
        .toList();
}
```

**当前配置（application.yml）：**

```yaml
knowledge:
  search:
    fts-config: simple          # 生产环境覆盖为 jieba
  reranker:
    enabled: false              # 默认关闭
    base-url: http://localhost:8001
    model-name: bge-reranker-v2-m3
    timeout-seconds: 10
```

**对话服务默认参数（KnowledgeServiceClient）：**

```yaml
knowledge:
  search:
    top-k: 5                    # 默认 topK
```

### 2.3 数据库层

**向量检索 SQL（selectByVector）：**

```sql
SELECT ..., (1 - (content_vector <=> #{queryVector, typeHandler=PgVectorTypeHandler})) AS score
FROM knowledge_chunk
WHERE kb_id = #{kbId}
  AND doc_status = 'PUBLISHED'
  AND retrieval_weight > 0
ORDER BY content_vector <=> #{queryVector, typeHandler=PgVectorTypeHandler}
LIMIT #{topK}
```

**全文检索 SQL（selectByFullText）：**

```sql
SELECT ..., ts_rank_cd(content_tsv, plainto_tsquery('${tsConfig}', #{query})) AS score
FROM knowledge_chunk
WHERE kb_id = #{kbId}
  AND doc_status = 'PUBLISHED'
  AND retrieval_weight > 0
  AND content_tsv @@ plainto_tsquery('${tsConfig}', #{query})
ORDER BY score DESC
LIMIT #{topK}
```

## 3. 问题根因分析

### 3.1 问题一：召回池过小，混合检索优势被压缩

**现象：**

当前每路召回数量固定为 `topK * 2`。对话服务默认 `topK=5`，因此每路实际只召回 **10 条**，两路合并去重后送入 RRF 的候选池最多 **20 条**，取出 5 条返回。

**根因：**

"最终返回数量（topK）"与"召回阶段数量（recallK）"绑定在同一个参数上，导致两个完全不同职责的参数互相干扰：

```
topK=5  →  每路召回 10 条  →  候选池 ≤ 20 条  →  RRF 取 5 条
topK=20 →  每路召回 40 条  →  候选池 ≤ 80 条  →  RRF 取 20 条
```

混合检索的设计初衷是：用 **大召回池** 覆盖两种检索方式各自的盲区，再通过融合与精排找出最相关的少量结果。当召回池只有 20 条时，BM25 和向量检索各自的召回优势根本无法体现。

**量化影响：**

以"召回率@100"评估为例，每路召回 10 条 vs 80 条时，相关文档被召回的概率差距通常在 **15%~30%**（取决于知识库大小与查询类型）。这个差距在精排阶段无法弥补——Reranker 只能从候选中挑选，无法凭空召回被遗漏的相关文档。

---

### 3.2 问题二：BGE-Reranker 默认关闭，精排能力闲置

**现象：**

```yaml
knowledge:
  reranker:
    enabled: false   # 生产环境也是 false
```

`RerankService` 已经有完整实现：HTTP 调用 `/rerank` 接口、Resilience4j Circuit Breaker 降级、超时控制。接入代码完善，只是开关没有打开。

**根因分析：**

Reranker 被关闭可能出于以下考虑：
1. 担心引入额外延迟
2. 召回池只有 20 条，Reranker 的提升有限，性价比低

但这两个顾虑在召回池扩大后会发生变化：
- 延迟：BGE-Reranker-v2-M3 对 100 条候选的推理延迟约 50~100ms（GPU），Circuit Breaker 已做降级保护
- 效果：候选池从 20 条扩大到 200 条后，Reranker 的精排作用才真正显现

问题一和问题二互为因果：**召回池小，Reranker 没用；Reranker 没用，召回池小也无所谓。**

---

### 3.3 问题三：RRF 的 K 值与场景不匹配

**现象：**

```java
private static final int DEFAULT_K = 60;
// RrfUtils.fuse 始终用 K=60
```

**根因：**

K=60 是 Cormack 等人 2009 年原始论文中针对**大规模文档集、topK 较大**场景（如 top-1000）的推荐值。其作用是平滑不同排名位置之间的分值差距，避免头部结果分值过于集中。

当 topK 很小（如 5~20）时，K=60 会使 RRF 对头部排名的区分度不足——第 1 名和第 5 名的得分差距被过度平滑，导致融合结果不够"尖锐"。

理论值：
- K=60, rank=1: score = 1/61 ≈ 0.0164
- K=60, rank=5: score = 1/65 ≈ 0.0154
- 差距仅 6%

- K=30, rank=1: score = 1/31 ≈ 0.0323
- K=30, rank=5: score = 1/35 ≈ 0.0286
- 差距 11%

对于 topK=5~20 的场景，K 调低到 **30~40** 能让 RRF 对头部结果更敏感。

---

### 3.4 问题四：向量检索与全文检索分配相同数量，未区分优先级

**现象：**

```java
chunkRepository.vectorSearch(queryVector, topK * 2, kbId)   // 同数量
chunkRepository.fullTextSearch(query,     topK * 2, kbId)   // 同数量
```

**根因：**

BM25 全文检索的计算量远低于向量 ANN 检索，且 BM25 对于精确匹配（产品型号、专业术语）的精准率更高，是强保底策略。在中文客服场景下，用户经常输入精确的产品名称或错误代码，BM25 应该被赋予更多召回配额。

业界通行做法（如 BAAI 官方 RAG 指南）是：BM25 召回数略多于向量检索，比例约为 **6:4 ~ 5:5**，根据知识库内容特点调整。

## 4. 方案调研对比

### 4.1 融合策略对比

业界主流的双路召回融合方案有四种，下表从算法原理、工程复杂度、中文适配性等维度做横向对比：

| 维度 | RRF（当前） | Min-Max 加权融合 | DBSFusion | SPLADE 稀疏向量 |
|---|---|---|---|---|
| **算法原理** | `1/(K+rank)` 基于排名 | 归一化原始分值后加权 | Z-score 归一化后加权 | 训练型稀疏向量替代 BM25 |
| **是否需要训练数据** | 否 | 否（需调权重） | 否（需调权重） | 是（需微调模型） |
| **量纲敏感性** | 完全免疫 | 敏感，受异常值影响 | 较强健壮性 | N/A（单路输出） |
| **实现复杂度** | 低 ✅ | 低 | 中 | 高 |
| **权重可调性** | 通过 K 值间接调 | α/β 直接控制 | 分布参数控制 | 模型权重决定 |
| **中文适配** | 中性 | 中性 | 中性 | 需中文训练语料 |
| **代表框架** | LlamaIndex、Haystack | Elasticsearch 8.x | LangChain EnsembleRetriever | ColBERT-v2、Naver SPLADE |
| **生产稳定性** | 高，15年验证 | 高 | 中（较新） | 低（部署复杂） |

**结论：对于当前技术栈（pgvector + 无标注数据），RRF 仍是最优选择。**

---

### 4.2 召回数量策略对比

| 策略 | 描述 | 优点 | 缺点 |
|---|---|---|---|
| **固定乘数（当前：topK×2）** | 召回数 = topK × 固定系数 | 简单 | topK 小时候选池极小；topK 大时浪费资源 |
| **固定下限（推荐）** | 召回数 = max(topK×N, 固定下限) | 低 topK 时有保底 | 略增配置复杂度 |
| **完全独立参数** | recallK 与 topK 完全解耦 | 最灵活 | 需单独运维两套参数 |
| **自适应（动态）** | 根据 kbId 知识库大小动态调整 | 精细化 | 实现复杂，可观测性差 |

业界实践参考：

- **LlamaIndex HybridRetriever**：默认每路召回 `similarity_top_k=2` 倍于最终 top-k，实际推荐配置 50~100
- **Haystack InMemoryBM25Retriever**：默认 `top_k=10`，官方文档建议 Reranker 前送入至少 50 条
- **BAAI/FlagEmbedding 官方 RAG 指南**：建议送入 Reranker 的候选数为 **100~200 条**，超过 200 后 BGE-Reranker-v2-M3 的延迟增长明显，但效果提升趋于平缓
- **Elasticsearch 官方混合搜索示例**：每路召回 50 条，合并后 100 条送精排，最终取 top-10

---

### 4.3 Reranker 原理：与向量模型的区别

#### Bi-Encoder（向量模型，当前用于召回）

```
query  → Embedding Model → [0.12, 0.87, ...]  ─┐
                                                  ├─ cosine(q, d) = 0.83
doc    → Embedding Model → [0.15, 0.91, ...]  ─┘
```

query 和文档**各自独立**编码成向量，两者互不感知。文档向量可以**提前离线计算**存入 pgvector，查询时只需向量化 query 做 ANN 检索，速度极快（毫秒级，支持千万级向量库）。

代价是精度有损耗——余弦相似度只是粗略估计，无法捕捉细粒度的语义关联，例如：
- query "空调不制冷" 和文档 "制冷剂泄漏排查步骤" 向量相似度可能不高，但实际高度相关
- 精确型号 "iPhone 15 256GB" 容易被语义相近但实际无关的结果干扰

#### Cross-Encoder（Reranker，用于精排）

```
[CLS] query [SEP] doc_1 [SEP] → BERT → 相关性分数 0.97
[CLS] query [SEP] doc_2 [SEP] → BERT → 相关性分数 0.23
[CLS] query [SEP] doc_3 [SEP] → BERT → 相关性分数 0.81
```

把 query 和文档**拼成一段文本**一起送进模型。内部 Self-Attention 让 query 的每个词都能"看到"文档的每个词，反之亦然。这种**交叉注意力**使模型能判断：

- "这段文档在回答这个问题吗？"
- "query 里的核心意图词和文档的哪部分对应？"
- "文档的隐含语义是否匹配 query 的真实需求？"

代价是**文档无法预先计算**，每次查询都要把 query+每个候选文档重新推理一遍，100 条候选就是 100 次前向传播，耗时数十毫秒到几百毫秒。

#### 为什么 RAG 要两者配合

```
海量知识库（数万条 chunk）
        │
        │  ① 向量检索（Bi-Encoder）：快，允许有误差，用于粗筛
        │     pgvector ANN，毫秒级，每秒处理数千请求
        ↓
候选集（80~200 条）
        │
        │  ② BGE-Reranker（Cross-Encoder）：慢但准，用于精排
        │     只处理小候选集，延迟可接受（~80ms）
        ↓
最终结果（5~20 条，送给 LLM 构建 RAG 上下文）
```

类比：向量检索像**简历筛选**（快速过滤明显不匹配的），Reranker 像**面试**（对剩余候选人深入评估）。面试比简历筛选准确得多，但不可能对所有人都面试。**召回池越大，面试的价值越高**——这也是召回数量扩大和 Reranker 激活必须同时做的根本原因。

#### BGE-Reranker-v2-M3 说明

BAAI（北京人工智能研究院）发布，"M3"含义：

| 字母 | 含义 | 说明 |
|---|---|---|
| **M**ultilingual | 多语言 | 支持 100+ 语言，中英双语效果尤佳 |
| **M**ulti-functionality | 多功能 | 既可做 Reranker，也可做 Embedding |
| **M**ulti-granularity | 多粒度 | 短查询和长文档均有良好表现 |

对中文客服场景（混杂中英文、行业术语、口语化表达），BGE-Reranker-v2-M3 是目前开源里综合表现最好的选择，系统已完整接入，只需打开开关即可生效。

---

### 4.4 Reranker 方案对比

| 方案 | 延迟（100条候选，GPU） | 中文效果 | 部署复杂度 | 适配现有接口 |
|---|---|---|---|---|
| **BGE-Reranker-v2-M3（当前接入）** | 50~100ms | ⭐⭐⭐⭐⭐ 专为中英双语设计 | 低（已接入） | ✅ 已有完整代码 |
| BGE-Reranker-v2-Large | 100~200ms | ⭐⭐⭐⭐⭐ | 低 | ✅ 同接口 |
| Cohere Rerank（云服务） | 200~500ms（网络） | ⭐⭐⭐⭐ | 极低（API调用） | 需改 HTTP 客户端 |
| Cross-Encoder 自训练 | 取决于模型大小 | 取决于训练数据 | 高 | 需重新开发 |

**结论：BGE-Reranker-v2-M3 是当前最优选择，接口已就绪，只需打开开关。**

---

### 4.5 RRF K 值调研

原始论文（Cormack et al., 2009, SIGIR）将 K=60 用于 TREC 竞赛评测，候选文档数在 1000 量级。

后续研究（Lin, 2021; Saad-Falcon et al., 2023）表明：

- **K=60** 适合大候选池（500+）、topK 较大（top-100）的场景，分值平滑效果好
- **K=20~40** 适合小候选池（100~300）、topK 较小（top-5~20）的场景，头部区分度更好
- K 值对最终效果的影响通常在 **1%~3% MRR@10**，属于细节调优，不是主要优化方向

针对 Aria 的场景（候选池 200 条、topK 5~20），建议将 K 从 60 调整为 **40**，作为稳妥的折中值。

---

### 4.5 方案选型决策

综合以上调研，确定优化方向为：

1. **融合算法：保持 RRF**，微调 K 值（60→40）
2. **召回数量：解耦 recallK 与 topK**，引入独立配置参数
3. **精排：激活 BGE-Reranker**，明确候选上限（rerankerCandidateLimit）
4. **两路分配：BM25 略多于向量**（6:4），反映中文客服场景的字面匹配需求

## 5. 推荐方案详细设计

### 5.1 整体架构变化

**改造前：** `topK` 与召回数绑定，topK=5 时候选池仅 20 条

```mermaid
flowchart LR
    Q(["query · topK=5"]) --> E[Embedding]
    E --> V["vectorSearch\n❌ topK×2 = 10条"]
    E --> T["fullTextSearch\n❌ topK×2 = 10条"]
    V --> R["RRF K=60\n直接取 topK=5"]
    T --> R
    R --> X["Rerank\n默认关闭 ×"]
    X --> O(["返回 5 条\n候选池仅 20 条"])

    style V fill:#fdd,stroke:#f88
    style T fill:#fdd,stroke:#f88
    style X fill:#eee,stroke:#ccc,color:#999
```

**改造后：** `recallK` 独立配置，大池子召回 → RRF 融合 → Reranker 精排 → 截断 topK

```mermaid
flowchart LR
    Q(["query · topK=5"]) --> E[Embedding]
    E --> V["vectorSearch\n✅ recallKVector = 80条"]
    E --> T["fullTextSearch\n✅ recallKText = 100条"]
    V --> R["RRF K=40\n取 ≤200条候选"]
    T --> R
    R --> RK["BGE-Reranker\n✅ 精排开启"]
    RK --> LM["limit(topK=5)"]
    LM --> O(["返回 5 条\n候选池 180 条"])

    style V fill:#dfd,stroke:#8c8
    style T fill:#dfd,stroke:#8c8
    style RK fill:#dfd,stroke:#8c8
```

---

### 5.2 新增配置参数

在 `application.yml` 中新增以下配置项，通过 `@ConfigurationProperties` 绑定：

```yaml
knowledge:
  search:
    fts-config: simple                 # 不变
    recall-k-vector: 80               # 【新增】向量召回数，独立于 topK
    recall-k-text: 100                # 【新增】BM25 召回数，独立于 topK
    reranker-candidate-limit: 200     # 【新增】送入 Reranker 的候选上限
    rrf-k: 40                         # 【新增】RRF 平滑系数，覆盖默认 60
  reranker:
    enabled: true                     # 【修改】生产环境打开
    base-url: http://reranker:8001    # 生产环境地址
    model-name: bge-reranker-v2-m3
    timeout-seconds: 10
```

**参数说明：**

| 参数 | 默认值 | 说明 | 调优方向 |
|---|---|---|---|
| `recall-k-vector` | 80 | 向量 ANN 检索候选数 | 知识库大、多样性查询 → 调大 |
| `recall-k-text` | 100 | BM25 全文检索候选数 | 精确匹配多（产品编号等）→ 调大 |
| `reranker-candidate-limit` | 200 | RRF 融合后送 Reranker 的上限 | 受 Reranker GPU 算力约束 |
| `rrf-k` | 40 | RRF 平滑系数 | topK 大 → 调大；topK 小 → 调小 |

---

### 5.3 SearchProperties 配置类

新建 `SearchProperties.java`，集中管理检索参数：

```java
// ai-knowledge/knowledge-service/src/main/java/
// com/aria/knowledge/infrastructure/config/SearchProperties.java

@ConfigurationProperties(prefix = "knowledge.search")
@Validated
public record SearchProperties(

    @NotBlank
    String ftsConfig,

    @Min(10) @Max(500)
    int recallKVector,         // 默认 80

    @Min(10) @Max(500)
    int recallKText,           // 默认 100

    @Min(50) @Max(500)
    int rerankerCandidateLimit, // 默认 200

    @Min(1) @Max(100)
    int rrfK                   // 默认 40

) {
    public SearchProperties {
        // compact constructor 兜底
        if (recallKVector <= 0) recallKVector = 80;
        if (recallKText <= 0) recallKText = 100;
        if (rerankerCandidateLimit <= 0) rerankerCandidateLimit = 200;
        if (rrfK <= 0) rrfK = 40;
    }
}
```

---

### 5.4 KnowledgeSearchAppService 改造

**核心改动：** 将 `topK * 2` 替换为独立召回参数，RRF 先融合到 `rerankerCandidateLimit`，Rerank 后再截断到 `topK`。

```java
@Service
@RequiredArgsConstructor
public class KnowledgeSearchAppService {

    private final KnowledgeChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;
    private final SearchProperties searchProps;            // 【新增注入】

    @Autowired(required = false)
    private RerankService rerankService;

    @Qualifier("searchExecutor")
    private final Executor searchExecutor;

    private static final long SEARCH_TIMEOUT_SECONDS = 3L;

    public List<ChunkHit> hybridSearch(String query, String kbId, int topK) {
        float[] queryVector = embeddingService.encode(query);

        // 【改动】使用独立的召回参数，不再绑定 topK
        int recallVector = searchProps.recallKVector();   // 80
        int recallText   = searchProps.recallKText();     // 100

        CompletableFuture<List<ChunkHit>> vectorFuture = CompletableFuture.supplyAsync(
            () -> chunkRepository.vectorSearch(queryVector, recallVector, kbId),
            searchExecutor);
        CompletableFuture<List<ChunkHit>> textFuture = CompletableFuture.supplyAsync(
            () -> chunkRepository.fullTextSearch(query, recallText, kbId),
            searchExecutor);

        List<ChunkHit> vectorHits = safeGet(vectorFuture, "向量检索", kbId);
        List<ChunkHit> textHits   = safeGet(textFuture,   "全文检索", kbId);

        List<String> vectorIds = vectorHits.stream().map(ChunkHit::getChunkId).toList();
        List<String> textIds   = textHits.stream().map(ChunkHit::getChunkId).toList();

        // 【改动】RRF 融合到 rerankerCandidateLimit，不是 topK
        int candidateLimit = searchProps.rerankerCandidateLimit(); // 200
        int rrfK           = searchProps.rrfK();                   // 40
        List<String> fusedIds = RrfUtils.fuseWithK(candidateLimit, rrfK,
            List.of(vectorIds, textIds));

        // 重建 ChunkHit，向量结果优先（putIfAbsent 不变）
        Map<String, ChunkHit> hitMap = new LinkedHashMap<>();
        vectorHits.forEach(h -> hitMap.putIfAbsent(h.getChunkId(), h));
        textHits.forEach(h ->   hitMap.putIfAbsent(h.getChunkId(), h));

        List<ChunkHit> candidates = fusedIds.stream()
            .map(hitMap::get)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        // 【改动】Rerank 后再截断到 topK（而不是 RRF 直接截断）
        return rerank(query, candidates).stream()
            .limit(topK)
            .collect(Collectors.toList());
    }

    private List<ChunkHit> rerank(String query, List<ChunkHit> candidates) {
        if (rerankService == null || candidates.isEmpty()) {
            return candidates;
        }
        try {
            return rerankService.rerank(query, candidates);
        } catch (Exception e) {
            log.warn("[rerank] 精排失败，降级返回 RRF 结果. error={}", e.getMessage());
            return candidates;
        }
    }

    // safeGet 不变
    private List<ChunkHit> safeGet(CompletableFuture<List<ChunkHit>> future,
                                    String label, String kbId) {
        try {
            return future.get(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("[hybridSearch] {} 超时降级. kbId={}", label, kbId);
            future.cancel(true);
            return List.of();
        } catch (Exception e) {
            log.error("[hybridSearch] {} 异常降级. kbId={}", label, kbId, e);
            return List.of();
        }
    }
}
```

---

### 5.5 RrfUtils 无需改动

`fuseWithK(int topK, int k, List<List<String>> lists)` 接口已支持自定义 K 值，只需在调用侧传入 `searchProps.rrfK()` 即可。不修改 `RrfUtils.java`。

---

### 5.6 数据库层无需改动

`selectByVector` 和 `selectByFullText` 的 `LIMIT #{topK}` 参数已通过 MyBatis 动态传入，召回数量的变化只影响参数值，不需要修改 SQL 或 Mapper。

---

### 5.7 延迟影响评估

| 阶段 | 改造前（topK=5） | 改造后 | 增量 |
|---|---|---|---|
| Embedding encode | ~20ms | ~20ms | 0 |
| 向量检索（pgvector） | ~5ms（10条） | ~15ms（80条） | +10ms |
| 全文检索（FTS） | ~3ms（10条） | ~8ms（100条） | +5ms |
| RRF 融合 | <1ms | <1ms | 0 |
| BGE-Reranker（GPU） | 跳过 | ~80ms（200条） | +80ms |
| **总计** | **~28ms** | **~123ms** | **+95ms** |

对于客服场景（用户期待的响应时间 1~3s），增加约 100ms 在可接受范围内。Reranker 的 Circuit Breaker 保证了在 Reranker 不可用时自动降级，不影响可用性。

若对延迟敏感，可将 `reranker-candidate-limit` 调低至 100 条，Reranker 延迟可降至约 40ms。

---

### 5.8 请求时序图

#### 正常流程

```mermaid
sequenceDiagram
    autonumber
    participant Client as 对话服务 FaqChatAppService
    participant Search as KnowledgeSearchAppService
    participant Embed as EmbeddingService
    participant Vec as ChunkRepository vectorSearch
    participant Fts as ChunkRepository fullTextSearch
    participant RRF as RrfUtils
    participant Rank as RerankService BGE-Reranker

    Client->>Search: hybridSearch(query, kbId, topK=5)

    Search->>Embed: encode(query)
    Embed-->>Search: float[] queryVector ~20ms

    par searchExecutor 线程池并行
        Search-)Vec: vectorSearch(queryVector, recallKVector=80, kbId)
        Note over Vec: pgvector 余弦距离 LIMIT 80
        Vec-->>Search: vectorHits 最多80条 ~15ms
    and
        Search-)Fts: fullTextSearch(query, recallKText=100, kbId)
        Note over Fts: ts_rank_cd plainto_tsquery LIMIT 100
        Fts-->>Search: textHits 最多100条 ~8ms
    end

    Note over Search: safeGet 超时保护 3s

    Search->>RRF: fuseWithK(candidateLimit=200, rrfK=40, [vectorIds, textIds])
    Note over RRF: score = 1/(40+rank+1) 按分值降序取最多200条
    RRF-->>Search: fusedIds 最多180条去重后

    Search->>Rank: rerank(query, candidates 最多180条)
    Note over Rank: BGE-Reranker-v2-M3 交叉编码打分 ~80ms
    Rank-->>Search: rerankedHits 按相关度重排

    Search->>Search: limit(topK=5)
    Search-->>Client: List ChunkHit 5条 总计~123ms
```

#### 降级场景一：向量检索超时

```mermaid
sequenceDiagram
    autonumber
    participant Client as 对话服务
    participant Search as KnowledgeSearchAppService
    participant Vec as ChunkRepository vectorSearch
    participant Fts as ChunkRepository fullTextSearch
    participant RRF as RrfUtils
    participant Rank as RerankService

    Client->>Search: hybridSearch(query, kbId, topK=5)

    par searchExecutor 并行
        Search-)Vec: vectorSearch(...)
        Note over Vec: pgvector 响应超时 超过3s
        Vec--xSearch: TimeoutException
        Note over Search: safeGet 捕获超时 future.cancel 降级返回空列表
    and
        Search-)Fts: fullTextSearch(...)
        Fts-->>Search: textHits 100条
    end

    Search->>RRF: fuseWithK(200, 40, [空列表, textIds])
    Note over RRF: 仅 textHits 单路参与融合
    RRF-->>Search: fusedIds 最多100条

    Search->>Rank: rerank(query, candidates)
    Rank-->>Search: rerankedHits

    Search-->>Client: List ChunkHit 5条 服务正常 BM25兜底
```

#### 降级场景二：Reranker Circuit Breaker 熔断

```mermaid
sequenceDiagram
    autonumber
    participant Client as 对话服务
    participant Search as KnowledgeSearchAppService
    participant Vec as ChunkRepository vectorSearch
    participant Fts as ChunkRepository fullTextSearch
    participant RRF as RrfUtils
    participant CB as CircuitBreaker Resilience4j
    participant Rank as RerankService

    Client->>Search: hybridSearch(query, kbId, topK=5)

    par searchExecutor 并行
        Search-)Vec: vectorSearch(...)
        Vec-->>Search: vectorHits 正常
    and
        Search-)Fts: fullTextSearch(...)
        Fts-->>Search: textHits 正常
    end

    Search->>RRF: fuseWithK(200, 40, [vectorIds, textIds])
    RRF-->>Search: fusedIds 最多180条

    Search->>CB: rerank(query, candidates)
    Note over CB: Circuit OPEN 失败率超阈值
    CB--xSearch: CallNotPermittedException
    Note over Search: catch Exception log.warn精排失败 降级返回RRF结果

    Search->>Search: limit(topK=5) 取RRF前5条
    Search-->>Client: List ChunkHit 5条 降级但服务正常
```

## 6. 实施计划

### 6.1 改动范围汇总

| 文件 | 改动类型 | 说明 |
|---|---|---|
| `application.yml` | 新增配置项 | recall-k-vector/text、reranker-candidate-limit、rrf-k |
| `application-prod.yml` | 修改配置项 | reranker.enabled=true、生产参数调整 |
| `SearchProperties.java` | 新建文件 | @ConfigurationProperties 绑定类 |
| `KnowledgeSearchAppService.java` | 修改逻辑 | 注入 SearchProperties，替换 topK*2，调整 RRF 参数 |
| `KnowledgeAutoConfiguration.java`（或同类配置类） | 新增注解 | @EnableConfigurationProperties(SearchProperties.class) |

**不涉及改动：**
- `RrfUtils.java` — 现有 `fuseWithK` 接口已满足需求
- `KnowledgeChunkMapper.xml` — SQL 无需修改，LIMIT 参数动态传入
- `RerankService.java` — 接口完整，只修改开关配置
- `KnowledgeChunkRepository.java` — 接口签名不变

---

### 6.2 阶段一：参数解耦（低风险，1人天）

**Step 1：新建 SearchProperties**

```
ai-knowledge/knowledge-service/src/main/java/
  com/aria/knowledge/infrastructure/config/
    SearchProperties.java     ← 新建
```

```java
@ConfigurationProperties(prefix = "knowledge.search")
@Validated
public record SearchProperties(

    @NotBlank
    String ftsConfig,

    @Min(10) @Max(500)
    @DefaultValue("80")
    int recallKVector,

    @Min(10) @Max(500)
    @DefaultValue("100")
    int recallKText,

    @Min(50) @Max(500)
    @DefaultValue("200")
    int rerankerCandidateLimit,

    @Min(1) @Max(100)
    @DefaultValue("40")
    int rrfK
) {}
```

**Step 2：更新 application.yml**

```yaml
knowledge:
  search:
    fts-config: simple
    recall-k-vector: 80
    recall-k-text: 100
    reranker-candidate-limit: 200
    rrf-k: 40
```

**Step 3：改造 KnowledgeSearchAppService**

将 `topK * 2` 替换为 `searchProps.recallKVector()` / `searchProps.recallKText()`，将 `RrfUtils.fuse(topK, ...)` 替换为 `RrfUtils.fuseWithK(candidateLimit, rrfK, ...)`，Rerank 后 `.limit(topK)`。

---

### 6.3 阶段二：激活 Reranker（中风险，0.5人天）

**Step 1：生产环境配置**

在 `application-prod.yml` 中：

```yaml
knowledge:
  reranker:
    enabled: true
    base-url: http://reranker-service:8001    # 实际服务地址
    model-name: bge-reranker-v2-m3
    timeout-seconds: 10
```

**Step 2：确认 Reranker 服务部署**

- [ ] BGE-Reranker-v2-M3 服务（infinity-emb 或 Xinference）已部署
- [ ] `/rerank` 接口可通，返回格式与 `RerankService` 期望一致
- [ ] Circuit Breaker 降级测试：停掉 Reranker 服务，确认 `KnowledgeSearchAppService` 正常降级为 RRF 结果

**Step 3：灰度上线**

建议先在测试知识库上验证，对比开启前后的搜索结果质量，再逐步推到生产。

---

### 6.4 阶段三：可观测性增强（低风险，0.5人天）

在 `KnowledgeSearchAppService` 中增加结构化日志，支持后期数据分析：

```java
// 在 hybridSearch 返回前记录
log.info("[hybridSearch] kbId={} query_len={} vector_hits={} text_hits={} " +
         "fused={} after_rerank={} topK={}",
    kbId, query.length(),
    vectorHits.size(), textHits.size(),
    fusedIds.size(), result.size(), topK);
```

日志字段说明：

| 字段 | 用途 |
|---|---|
| `vector_hits` | 向量召回实际数量（低于 recallKVector 说明知识库内容少） |
| `text_hits` | BM25 召回实际数量（为 0 说明无字面匹配，依赖向量兜底） |
| `fused` | RRF 融合后候选数（接近 rerankerCandidateLimit 说明去重率低） |
| `after_rerank` | Rerank 后数量（等于 topK 为正常） |

---

### 6.5 回滚方案

所有改动均通过配置参数控制，无需改动 SQL 或数据库结构。回滚只需：

```yaml
# 回滚配置（恢复旧行为）
knowledge:
  search:
    recall-k-vector: 10     # 等效于原 topK*2（topK=5时）
    recall-k-text: 10
    reranker-candidate-limit: 10
    rrf-k: 60
  reranker:
    enabled: false
```

---

### 6.6 测试用例

| 测试场景 | 验证点 |
|---|---|
| `topK=5`，向量和 BM25 各召 80/100 条 | 候选池 ≤ 180 条，RRF 输出 ≤ 200 条，最终返回 5 条 |
| Reranker 服务不可用 | Circuit Breaker 触发，降级返回 RRF 前 5 条，无异常抛出 |
| 查询无 BM25 匹配（text_hits=0） | 仅向量结果参与 RRF，正常返回 topK 条 |
| 向量检索超时（模拟 3s+） | 降级为空列表，全文检索结果独立返回 |
| 知识库内容少（总 chunk < recallK） | 召回数自然小于配置值，不报错，正常返回 |
| `topK=50`（最大值） | 候选池 ≤ 180 条 > topK，正常截断 |

## 7. 预期收益与验收指标

### 7.1 预期收益

**召回质量提升：**

| 指标 | 改造前（估算） | 改造后（估算） | 依据 |
|---|---|---|---|
| 召回率 @topK=5 | ~60%（候选池20条） | ~82%（候选池180条） | 候选池扩大9倍，BEIR 基准数据 |
| MRR@5（平均倒数排名） | 基线 | +15%~25% | BGE-Reranker-v2-M3 官方评测 |
| 精确型号查询命中率 | ~70% | ~85% | BM25 召回配额增加 |
| 同义词/语义查询命中率 | ~65% | ~80% | 向量召回候选扩大 |

> 注：以上数据为基于业界同类场景的估算，实际效果以生产 A/B 测试为准。

**系统稳定性：**

- Reranker Circuit Breaker 保障：Reranker 不可用时自动降级，P99 延迟不受影响
- 参数化配置：无需重新部署即可调整召回数量和 RRF 参数

---

### 7.2 验收指标

#### 7.2.1 功能验收

- [ ] `recall-k-vector=80` 时，向量检索实际返回 ≤ 80 条（知识库内容充足时）
- [ ] `recall-k-text=100` 时，全文检索实际返回 ≤ 100 条
- [ ] RRF 输出条数 ≤ `reranker-candidate-limit`（200）
- [ ] 最终返回条数 = `topK`（候选充足时）
- [ ] Reranker 关闭时，行为与改造前等效（仅召回数量不同）
- [ ] Reranker 超时/异常时，服务正常降级，不抛出 500

#### 7.2.2 性能验收

| 场景 | P50 延迟目标 | P99 延迟目标 |
|---|---|---|
| Reranker 开启，正常请求 | < 200ms | < 500ms |
| Reranker 降级（Circuit Breaker 触发） | < 100ms | < 200ms |
| 高并发（50 QPS） | < 300ms | < 800ms |

#### 7.2.3 质量验收（人工评测）

选取 50 条真实客服查询（覆盖精确匹配、语义匹配、中文同义词三类），对比改造前后：

- **命中率**：返回结果中包含正确答案的比例，目标提升 ≥ 10%
- **MRR@5**：正确答案排在第几位的倒数均值，目标提升 ≥ 15%
- **误召回率**：返回明显不相关内容的比例，目标不增加

---

### 7.3 监控与持续调优

#### 上线后监控项

```
# Grafana 面板建议指标
knowledge_search_vector_hits_count{kbId}      # 向量召回实际数量分布
knowledge_search_text_hits_count{kbId}        # BM25 召回实际数量分布
knowledge_search_fused_count{kbId}            # RRF 融合后候选数分布
knowledge_reranker_latency_ms                 # Reranker 调用延迟
knowledge_reranker_circuit_breaker_state      # Circuit Breaker 状态
knowledge_search_total_latency_ms{kbId}       # 端到端搜索延迟
```

#### 参数调优建议

运行 2 周后，根据监控数据判断是否需要调整：

| 观测现象 | 调整建议 |
|---|---|
| `text_hits` 经常为 0 | BM25 匹配率低，考虑换 `jieba` 分词或降低 `recall-k-text` |
| `fused` 远小于 `rerankerCandidateLimit` | 两路重叠度高，可适当降低 `recall-k-vector/text` |
| Reranker P99 > 500ms | 降低 `reranker-candidate-limit`（200→100） |
| 用户反馈搜索结果不准 | 考虑收集标注数据，引入 DBSFusion 或加权 RRF |

---

### 7.4 后续演进方向（非本次范围）

| 方向 | 时机 | 说明 |
|---|---|---|
| 查询改写（Query Rewriting） | 有足够日志后 | 对模糊查询先扩写再检索 |
| 加权 RRF / DBSFusion | 有标注数据后 | 基于人工评测结果调整两路权重 |
| SPLADE 稀疏向量 | 中长期 | 替换 BM25，兼顾字面匹配和语义泛化 |
| 多路召回（3路+） | 知识库规模扩大后 | 加入标题检索、Q&A 精确匹配等专项通道 |
| 检索结果缓存 | 高并发场景 | 对热门查询向量相似度缓存 topK 结果 |
