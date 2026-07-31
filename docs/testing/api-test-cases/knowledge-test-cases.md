# knowledge-service 接口测试用例

服务：`knowledge-service`（端口 8081，网关前缀 `https://localhost/knowledge`）

## 目录

1. 文档上传与文件类型推断
2. 摄入状态机（DRAFT/REVIEW/PUBLISHED/DEPRECATED/FAILED）
3. 审核（review）
4. 下线（offline / batch-offline）
5. Chunk 管理
6. 检索（hybrid search）
7. QA 手动录入
8. 翻译
9. 文档预览/统计
10. 内部接口（search / rerank）

> 说明：knowledge-service 的真实摄入状态机只有 **DRAFT / REVIEW / PUBLISHED / DEPRECATED / FAILED** 五个值，代码中不存在 PARSING/CHUNKING/EMBEDDING/PENDING_REVIEW 等中间态。测试用例以代码实际行为为准。

---

## 1. 文档上传与文件类型推断

`POST /api/knowledge/docs/upload?kbId={kbId}`（multipart，字段名 `file`）

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| KNOW-001 | 上传 txt 文档成功 | 已登录 admin token，kbId 存在 | 上传一个 `.txt` 文件 | 200，`data.docId` 非空，DB 中新记录 `status=DRAFT` | P0 | 后续摄入是异步的，本用例只验证上传接口本身 |
| KNOW-002 | 无 token 上传被拒 | - | 不带 Authorization 上传 | 401 | P0 | |
| KNOW-003 | `.pdf` 后缀识别为 PDF | 同 KNOW-001 | 上传 `.pdf` 文件 | 200，摄入时按 PDF 解析器处理（可通过后续 `/status` 及最终内容验证） | P1 | |
| KNOW-004 | `.docx` 后缀识别为 DOCX | 同上 | 上传 `.docx` 文件 | 200，按 DOCX 解析器处理 | P1 | |
| KNOW-005 | `.html`/`.htm` 后缀识别为 HTML | 同上 | 上传 `.html` 文件 | 200，按 HTML 解析器处理 | P1 | |
| KNOW-006 | 未知后缀落到 MARKDOWN（非报错） | 同上 | 上传 `.xyz` 或无后缀文件 | 200（不报错），`fileType` 被判定为 MARKDOWN，按纯文本解析 | P1 | **缺陷验证**：`resolveFileType` 对未知后缀无拒绝逻辑，任意文件都会被接受当作 Markdown 处理，不会返回 400 |
| KNOW-007 | 超大文件上传无语义化错误 | 同上 | 上传超过 Spring 默认 multipart 限制（1MB，若未覆盖配置）的文件 | 实际观察到 `MaxUploadSizeExceededException` 未被专门捕获，兜底走 `GlobalExceptionHandler.handleUnknown` 返回 500，而非语义化的 400/413 | P2 | **缺陷验证**：需先确认目标环境 `spring.servlet.multipart.max-file-size` 实际配置值，再据此设计具体超限字节数 |
| KNOW-008 | kbId 不存在时上传 | 同 KNOW-001，kbId 用不存在的值 | 上传文件，`kbId=not-exist-kb` | 需实测确认：Service 层未见对 kbId 存在性做前置校验，预期 200 且文档记录挂在一个不存在的 kbId 下 | P2 | 视为潜在数据一致性问题，非阻断性缺陷 |
| KNOW-009 | 上传后 MinIO 与 DB 原子性（回滚场景） | 构造 DB 写入失败场景（不易直接模拟，可标记为设计说明用例） | - | 事务失败时 `afterRollback` 会补偿删除已上传的 MinIO 孤儿文件 | P2 | 若无法直接构造失败场景，此用例可降级为代码复核项，暂不自动化 |

---

## 2. 摄入状态机

`GET /api/knowledge/docs/{id}/status` 查询摄入进度；摄入本身是上传后自动触发的异步 MQ 流程。

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| KNOW-010 | 正常内容摄入成功进入 REVIEW/PUBLISHED | 上传一个内容充实（>20字符、有效字符密度高）的文本文件 | 轮询 `GET /{id}/status` 直到终态 | 最终状态为 REVIEW 或 PUBLISHED（取决于是否需要人工审核逻辑，以实测为准），不应停留在 DRAFT 超过合理时间（建议轮询上限 60s） | P0 | 依赖 Embedding 服务可用；若 Embedding 未配置，可能落到 FAILED，需在报告中区分环境问题与代码缺陷 |
| KNOW-011 | 质量过滤：全部切片不合格直接 FAILED | 上传内容极短（<20字符）或全是特殊字符的文件 | 轮询 `/status` | 状态变为 FAILED，且**不经过 DLQ 重试**（可通过摄入耗时很短来间接验证，未观察到 3 次重试的指数退避延迟） | P1 | 对应 `QualityFilterHandler.handle`：质量过滤失败是 `ctx.abort()`，非抛异常，事务正常提交 |
| KNOW-012 | MQ 重试耗尽进 DLQ 标记 FAILED | 构造摄入 pipeline 中会抛异常的场景（如 Embedding 服务临时不可用，需要环境配合） | 上传文件，观察摄入耗时与最终状态 | 经过 3 次重试（间隔 1s→2s→4s，约 7s 左右）后进入 DLQ，最终状态 FAILED | P2 | 此用例依赖人为制造异常条件，若无法稳定复现可标记为手工验证项，不纳入 CI 常规回归 |
| KNOW-013 | retry：仅 FAILED 状态可重试 | 已有一个 FAILED 状态的文档 | `POST /api/knowledge/docs/{id}/retry` | 200，状态回到 DRAFT 并重新触发摄入 | P0 | |
| KNOW-014 | retry：非 FAILED 状态调用被拒 | 文档当前状态为 PUBLISHED | `POST /{id}/retry` | 业务码 5010（非法状态流转），非 200 | P1 | |
| KNOW-015 | reingest：仅 PUBLISHED 状态可用 | 已有 PUBLISHED 文档 | `POST /api/knowledge/docs/{id}/reingest` | 200，文档状态**不变**（仍 PUBLISHED），但重新触发摄入流程（`forceReingest=true`），旧 chunk 被新 chunk 幂等替换 | P0 | 验证方式：记录 reingest 前的 chunk 内容/tokenCount，reingest 后比对是否刷新 |
| KNOW-016 | reingest：非 PUBLISHED 状态调用被拒 | 文档状态为 DRAFT | `POST /{id}/reingest` | 400，`message` 含 "只有 PUBLISHED 状态的文档可以重新摄取" | P1 | |
| KNOW-017 | retry 与 reingest 的语义差异对照 | - | 对比 KNOW-013 与 KNOW-015 的请求/响应 | 明确记录两者目标状态、允许的前置状态、`forceReingest` 标志的不同 | P2 | 用于测试报告中区分文档说明，非独立断言用例 |

---

## 3. 审核（review）

`PUT /api/knowledge/docs/{id}/review`，请求体 `{"approved": true/false}`

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| KNOW-018 | REVIEW 状态审核通过 | 文档处于 REVIEW 状态 | `PUT /{id}/review {"approved":true}` | 200，状态变为 PUBLISHED | P0 | |
| KNOW-019 | REVIEW 状态审核拒绝 | 文档处于 REVIEW 状态 | `PUT /{id}/review {"approved":false}` | 200，状态变为 DRAFT | P0 | |
| KNOW-020 | DRAFT 状态审核拒绝触发非法流转 | 文档处于 DRAFT 状态 | `PUT /{id}/review {"approved":false}` | 业务码 5010（DRAFT→DRAFT 不在 `allowedTransitions` 内） | P1 | **缺陷验证**：这是容易被忽略的边界，DRAFT 状态下只有 `approved=true` 合法 |
| KNOW-021 | DRAFT 状态审核通过 | 文档处于 DRAFT 状态 | `PUT /{id}/review {"approved":true}` | 200，状态变为 PUBLISHED | P1 | |
| KNOW-022 | 已终态文档重复审核被拒 | 文档已是 PUBLISHED | `PUT /{id}/review {"approved":true}` | 业务码 5010 或 400（并发条件 UPDATE 未命中） | P1 | 需实测确认具体返回哪种错误，取决于状态机校验先行还是条件UPDATE先行 |
| KNOW-023 | 文档不存在时审核 | - | `PUT /api/knowledge/docs/not-exist-id/review {"approved":true}` | 业务码 4004 | P1 | |
| KNOW-024 | 并发审核冲突 | 文档处于 REVIEW，模拟并发调用两次 review | 几乎同时发出两个 review 请求 | 一个成功 200，另一个因条件 UPDATE `affected==0` 返回 400 "文档状态已被其他操作变更，请刷新后重试" | P2 | 并发场景，可用两个线程/两次快速连续请求模拟，不保证 100% 复现时序 |

---

## 4. 下线（offline / batch-offline）

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| KNOW-025 | 单个下线 PUBLISHED 文档 | 文档处于 PUBLISHED，且有关联 chunk | `DELETE /api/knowledge/docs/{id}` | 200，文档状态变为 DEPRECATED | P0 | |
| KNOW-026 | 下线后 chunk 是否被物理删除（缺陷验证） | 同上，记录下线前的 chunk 列表 | 下线后立即 `GET /{id}/chunks` | 实测确认：chunk 记录**仍然物理存在**（`offline()` 只改文档状态，不调用 chunk 删除），与 Controller 注释"物理删除所有chunk"不符 | P1 | **缺陷验证**：需在测试报告中明确标注文档注释与实现不一致 |
| KNOW-027 | 非 PUBLISHED 状态下线被拒 | 文档处于 DRAFT | `DELETE /{id}` | 业务码 5010（DRAFT 不在允许流转到 DEPRECATED 的状态集合内） | P1 | |
| KNOW-028 | 文档不存在时下线 | - | `DELETE /api/knowledge/docs/not-exist-id` | 业务码 4004 | P1 | |
| KNOW-029 | 并发下线冲突 | 文档处于 PUBLISHED | 几乎同时发出两次下线请求 | 一个成功，另一个因条件 UPDATE 未命中返回 400 | P2 | |
| KNOW-030 | batch-offline 空列表 | - | `POST /api/knowledge/docs/batch-offline {"docIds":[]}` | 200，无实际操作，不报错 | P1 | |
| KNOW-031 | batch-offline 超过 50 条被拒 | 构造 51 个文档 id 的数组（可以是不存在的 id，因为在数量校验之前就会拦截） | `POST /batch-offline {"docIds":[51个id]}` | 400，"批量操作最多支持 50 条" | P1 | |
| KNOW-032 | batch-offline 混合状态静默过滤 | 准备 3 个文档：1 个 PUBLISHED，2 个非 PUBLISHED（如 DRAFT） | `POST /batch-offline {"docIds":[3个id]}` | 200，只有 PUBLISHED 的那个被下线为 DEPRECATED，其余两个状态不变，且不报错 | P1 | 这里**不走** `DocStatus.transitionTo` 校验，是直接 filter + 批量 UPDATE，与单个 offline 逻辑不同，需在报告中说明 |

---

## 5. Chunk 管理

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| KNOW-033 | disable chunk 后不再被检索召回 | 已有 PUBLISHED 文档及可检索 chunk | 1) `POST /api/knowledge/chunks/{chunkId}/disable` 2) 用该 chunk 内容关键词调用 `POST /api/knowledge/docs/search-test` | disable 接口 200；检索结果中不再包含该 chunk（`retrieval_weight=0`，向量检索和全文检索 SQL 都过滤 `retrieval_weight > 0`） | P0 | |
| KNOW-034 | enable chunk 后恢复可检索 | 同上，chunk 已 disable | `POST /api/knowledge/chunks/{chunkId}/enable`，再检索 | 200，`retrieval_weight` 恢复为 1.0，检索结果重新包含该 chunk | P0 | |
| KNOW-035 | disable/enable 不存在的 chunk | - | `POST /api/knowledge/chunks/not-exist-id/disable` | 业务码 4004 "Chunk 不存在" | P1 | |
| KNOW-036 | disable 的 chunk 不计入 kb-stats | 已 disable 一个 chunk | `GET /api/knowledge/docs/kb-stats?kbId=xxx` | 统计结果（chunkCount/tokenSum）**不包含**已 disable 的 chunk（该接口按 `retrieval_weight>0` 过滤） | P1 | 与 stats（文档级统计）行为不同，见 KNOW-054 |
| KNOW-037 | disable 的 chunk 仍计入 stats（文档级） | 同上 | `GET /api/knowledge/docs/{docId}/stats` | 统计结果**包含**已 disable 的 chunk（该接口聚合不含权重过滤） | P1 | **差异点**：kb-stats 与 stats 对 disable chunk 的统计口径不一致，需在报告中标注 |
| KNOW-038 | updateContent 更新内容并重新向量化 | 已有 chunk，记录原 `tokenCount`/内容 | `PUT /api/knowledge/chunks/{chunkId}/content {"content":"新内容..."}` | 200，`content`/`tokenCount`/向量原子更新为一致状态；再次检索命中新内容而非旧内容 | P0 | |
| KNOW-039 | updateContent 空内容被拒 | 同上 | `PUT /{chunkId}/content {"content":""}` | 400，"Chunk 内容不能为空" | P1 | |
| KNOW-040 | updateContent 不存在的 chunk | - | `PUT /api/knowledge/chunks/not-exist-id/content {"content":"x"}` | 业务码 4004 | P1 | |

---

## 6. 检索（hybrid search）

`POST /api/knowledge/docs/search-test`（管理端调试接口）与内部 `POST /internal/knowledge/search`（供 conversation-service 调用）。

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| KNOW-041 | 正常检索返回相关结果 | 知识库已有已发布且内容相关的 chunk | `POST /api/knowledge/docs/search-test {"query":"...","kbId":"...","topK":5}` | 200，`data.hits` 非空，按相关度排序 | P0 | 依赖真实 Embedding 服务，标记 `ai` |
| KNOW-042 | 无匹配结果返回空数组而非报错 | 用完全无关的生僻查询词 | `POST /search-test {"query":"完全不相关的查询xyz123","kbId":"...","topK":5}` | 200，`hits=[]`，`totalFound=0`（非 404/500） | P1 | |
| KNOW-043 | 向量召回超时降级为空列表 | 难以直接构造，可标记为设计说明用例 | - | 向量或全文检索任一路超时（3s）会降级为空列表，不影响另一路结果的正常返回，不抛异常 | P2 | 若无法稳定构造超时条件，可标记为手工验证/代码复核项 |
| KNOW-044 | RRF 融合非简单加权（代码复核项） | - | - | 融合公式 `score(d)=Σ 1/(k+rank_i(d)+1)`，k 默认 40，非加权求和 | P2 | 属白盒逻辑，接口测试可通过对比不同 topK 结果排序间接验证，不作为强断言用例 |
| KNOW-045 | reranker 未配置时优雅降级 | 目标环境未配置 RERANKER 类型的 AI 模型 | 执行一次检索 | 200，返回结果为 RRF 融合顺序（未经过 rerank），不报错 | P1 | 需先查询 `GET /api/v1/admin/ai-models` 确认环境是否配置了 RERANKER，据此决定此用例走哪个分支 |
| KNOW-046 | topK 边界：management 接口上限 20 | - | `POST /search-test {"query":"...","kbId":"...","topK":21}` | 400（`@Max(20)` 校验） | P1 | |
| KNOW-047 | topK 边界：internal 接口上限 50 | 带 `X-Internal-Secret` | `POST /internal/knowledge/search {"query":"...","kbId":"...","topK":51}` | 400（`@Max(50)` 校验） | P1 | 两个接口上限不同，需分别验证 |
| KNOW-048 | topK 下限校验 | - | `POST /search-test {"query":"...","kbId":"...","topK":0}` | 400（`@Min(1)`） | P2 | |
| KNOW-049 | internal search 缺少 X-Internal-Secret | - | `POST /internal/knowledge/search` 不带密钥头 | 403，body `{"code":403,"message":"forbidden","data":null}` | P0 | |
| KNOW-050 | internal rerank 接口基本功能 | 带正确密钥 | `POST /internal/knowledge/rerank {"query":"...","chunks":[{"chunkId":"...","content":"...","score":0.5}]}` | 200，返回重排序后的列表 | P1 | 依赖 reranker 配置，若未配置则原样返回（见 KNOW-045） |
| KNOW-051 | internal rerank 空 chunks 列表 | 同上 | `POST /internal/knowledge/rerank {"query":"...","chunks":[]}` | 200，直接返回空列表，不报错 | P2 | |

---

## 7. QA 手动录入

`POST /api/knowledge/chunks/qa`

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| KNOW-052 | 正常录入 QA | 已有 kbId 和 docId（docId 状态任意） | `POST /api/knowledge/chunks/qa {"docId":"...","kbId":"...","question":"退货政策是什么","answer":"7天无理由退货"}` | 200，新增一条 chunk，内容格式为 `"Q：退货政策是什么\nA：7天无理由退货"`，`chunkType=TEXT`，`retrievalWeight=1.0` | P0 | |
| KNOW-053 | 录入 QA 立即可检索（不论文档真实状态） | 用一个状态为 **DRAFT**（未发布）的 docId 录入 QA | 1) `POST /chunks/qa` 用 DRAFT 状态的 docId 2) 立即用问题关键词检索 | 200 录入成功；检索能命中该 QA chunk（因为新 chunk 的 `docStatus` 被硬编码为 PUBLISHED，与所属文档真实状态无关） | P1 | **缺陷验证**：数据一致性问题，文档还未发布但 QA chunk 已可检索 |
| KNOW-054 | 录入 QA 不校验 docId/kbId 存在性 | - | `POST /chunks/qa {"docId":"not-exist-doc","kbId":"not-exist-kb","question":"x","answer":"y"}` | 200，成功插入一条游离的 chunk（Service 层未做存在性校验） | P1 | **缺陷验证** |
| KNOW-055 | question/answer 为空被拒 | - | `POST /chunks/qa {"docId":"...","kbId":"...","question":"","answer":"y"}` | 400，"问题和答案不能为空" | P1 | |
| KNOW-056 | Controller 层 `@NotBlank` 校验 | - | `POST /chunks/qa` 缺少必填字段（如缺 `kbId`） | 400（Bean Validation 拦截） | P2 | |

---

## 8. 翻译

`POST /api/knowledge/translate`。**注意**：整个 Controller 用 `@ConditionalOnProperty(name="knowledge.translate.enabled", havingValue="true", matchIfMissing=false)`，若目标环境未显式开启该配置项，此接口默认不存在。

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| KNOW-057 | 环境未开启翻译功能时接口不存在 | 确认 `knowledge.translate.enabled` 未配置或为 false | `POST /api/knowledge/translate {"text":"hello"}` | 404（`NoResourceFoundException`，Controller Bean 未注册） | P1 | 先检查目标环境配置决定走本用例还是下面的正向用例 |
| KNOW-058 | 正常翻译（环境已开启） | `knowledge.translate.enabled=true`，已配置 CHAT 类型默认模型 | `POST /api/knowledge/translate {"text":"Hello, how are you?"}` | 200，`data` 为简体中文翻译结果 | P0 | 依赖真实 LLM，标记 `ai` |
| KNOW-059 | text 为空被拒 | 同上 | `POST /translate {"text":""}` | 400，"翻译内容不能为空" | P1 | |
| KNOW-060 | 仅支持翻译成中文，不支持指定目标语言 | 同上 | 观察接口请求体，确认无 `targetLang` 等参数 | 接口硬编码 prompt 为"翻译成简体中文"，不支持其它语言方向 | P2 | 设计说明用例，非缺陷 |
| KNOW-061 | 翻译失败时 HTTP 200 但业务码 500（缺陷验证） | 同上，需构造下游 LLM 调用失败场景（如临时关闭模型服务或使用错误 baseUrl 配置） | `POST /translate` 触发下游异常 | **HTTP 状态码 200**，响应体 `{"code":500,"msg":"翻译失败：..."}`（Controller 内部 try/catch 返回 `R.fail`，不经过 GlobalExceptionHandler） | P1 | **缺陷验证**：与其它接口"异常经 GlobalExceptionHandler 返回对应 HTTP 状态码"的行为不一致，测试断言时不要按 500 状态码判断，需按业务码判断 |

---

## 9. 文档预览与统计

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| KNOW-062 | 预览 PDF 返回正确 Content-Type | 已上传 PDF 文档 | `GET /api/knowledge/docs/{docId}/preview` | 200，`Content-Type: application/pdf`（或对应映射类型），body 为原始字节流 | P1 | |
| KNOW-063 | 预览未知类型文档返回 octet-stream | fileType 落到未知分支的文档 | `GET /{docId}/preview` | 200，`Content-Type: application/octet-stream` | P2 | |
| KNOW-064 | 预览不存在的文档 | - | `GET /api/knowledge/docs/not-exist-id/preview` | 业务码 4004 | P1 | |
| KNOW-065 | 文件名转义防注入 | 上传文件名含 `\r\n` 等特殊字符（若上传接口允许） | `GET /{docId}/preview`，检查响应头 `Content-Disposition` | 文件名中的 `[\r\n"\\]` 已被移除，不能构造响应头注入 | P2 | 安全性验证用例 |
| KNOW-066 | chunks 列表返回完整字段 | 已有 chunk 的文档 | `GET /api/knowledge/docs/{docId}/chunks` | 200，每条含 `chunkId/pageNum/sectionTitle/chunkType/tokenCount/content/retrievalWeight` | P1 | |
| KNOW-067 | 文档不存在时 chunks/stats/preview 均返回 4004 | - | 分别调用三个接口传入不存在的 docId | 均为业务码 4004 | P1 | |
| KNOW-068 | kb-stats 对不存在的 kbId 返回全 0 而非 404 | - | `GET /api/knowledge/docs/kb-stats?kbId=not-exist-kb` | 200，`{"kbId":"not-exist-kb","docCount":0,"chunkCount":0,"tokenSum":0}` | P1 | 不校验 kbId 存在性 |
| KNOW-069 | kb-stats 的 docCount 仅统计 PUBLISHED 文档 | 知识库内有 DRAFT 和 PUBLISHED 混合状态文档 | `GET /kb-stats?kbId=xxx` | `docCount` 只计 PUBLISHED 状态的文档数 | P1 | |

---

## 10. 内部接口鉴权

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| KNOW-070 | 缺少 X-Internal-Secret 头 | - | `POST /internal/knowledge/search` 不带该头 | 403，`{"code":403,"message":"forbidden","data":null}` | P0 | 注意字段名是 `message` 不是 `msg`，与标准 `R` 结构不同 |
| KNOW-071 | 错误的密钥值 | - | 带 `X-Internal-Secret: wrong-secret` | 403，同上错误体 | P0 | |
| KNOW-072 | 正确密钥放行 | - | 带 `X-Internal-Secret: aria-internal-lycodeing-2024` | 请求正常进入 Controller 处理逻辑 | P0 | |
| KNOW-073 | 非 `/internal/**` 路径不受此过滤器拦截 | - | 访问任意 `/api/knowledge/**` 路径不带该头 | 不会被 InternalSecretFilter 拦截（可能因其它鉴权机制返回 401，但不是 403+forbidden 这个特定错误体） | P2 | 确认过滤器只拦截约定路径前缀 |

---
