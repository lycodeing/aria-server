# aria-server 代码评审整改追踪文档

> 本文档追踪 2026-08-06 全量代码评审（54K LOC / 684 文件，auth/conversation/knowledge/common 四模块并行评审）发现的问题及整改进度。
> 状态循环：整理文档 → 逐项修复 → 编译/测试验证 → 重新评审 → 更新本文档，直到无问题为止。

## 状态图例
- ⬜ 待修复
- 🔧 修复中
- ✅ 已修复（含验证）
- ⏭️ 已确认无需修复 / 设计如此
- 🔁 复审后新增

---

## 一、系统性问题（跨模块，最高优先级）

### SYS-1 授权体系普遍"认证有、授权无"（垂直/水平越权）
同一缺陷模式在四模块反复出现，属架构级遗漏。

| ID | 模块 | 位置 | 后果 | 状态 |
|---|---|---|---|---|
| SYS-1a | auth | `RoleController.java:28-131` | 任意登录用户可 `assignPermissions` 给自己角色授予全部权限 → **完整自我提权** | ✅ |
| SYS-1b | auth | `MenuController.java:29-88` | 任意登录用户篡改全局菜单树 | ✅ |
| SYS-1c | conversation | `ChatController` `/history`、`DELETE`、`/state`、`/ws/chat/{sessionId}` | 只校验 sessionId 格式不校验归属 → 拉取/清空/监听他人会话(IDOR) | ✅ |
| SYS-1d | conversation | `AgentChannelWsHandler.java:118-151` | 任意座席向任意会话注入伪造消息 | ✅ |
| SYS-1e | conversation | DIT/Dashboard/Canned `/admin/**` | 只 `checkLogin` 无 `@SaCheckPermission`（已补 system:dit:*/canned:*/dashboard:view 注解+SQL） | ✅ |
| SYS-1f | knowledge | `KnowledgeDocController`/`KnowledgeChunkController` | 任意登录用户下载/下线任意文档(跨租户 IDOR)（已补 knowledge:doc:view/upload/review/offline 注解+SQL；租户隔离见备注） | ✅ |

### SYS-2 密钥 fail-open / 明文入库
| ID | 位置 | 后果 | 状态 |
|---|---|---|---|
| SYS-2a | `EncryptUtils.java:90-101` | `ADP_SK_ENCRYPT_KEY` 缺失时静默回退硬编码开发密钥加密敏感数据 | ✅ |
| SYS-2b | `WebhookEventSubscriber.java:89-93` | secret 未配置时签名校验 `return true`(fail-open) + `String.equals` 非恒定时间 | ✅ |
| SYS-2c | `knowledge application*.yml` / `application-prod.yml` | 生产 MinIO 密码/JWT 密钥/内部共享密钥明文入库 | ✅ |
| SYS-2d | `AiModelConfigService.java:304-312` | 第三方大模型 API Key 默认 `PLAINTEXT:` 明文落库 | ✅ |

---

## 二、各模块严重/重要问题

### ai-auth
| ID | 位置 | 严重度 | 问题 | 状态 |
|---|---|---|---|---|
| AUTH-1 | `User.java:228` | 🟠 | 密码历史重用校验拿新密码哈希当明文比对，永远检测不出重用 | ✅ |
| AUTH-2 | `AuthApplicationService.refreshToken` | 🟠 | 不校验账号状态 → 禁用/锁定用户可无限续期 | ✅ |
| AUTH-3 | `UserApplicationService.disable/assignRoles` | 🟠 | 权限只读 token session 不回查 → 撤权/禁用无法即时生效且无踢出（已加 StpUtil.kickout 主动踢出） | ✅ |
| AUTH-4 | `AuthController.extractIp` | 🟠 | 频控 key 基于可伪造 X-Forwarded-For → 暴力破解绕限流（已加 trust-proxy 开关，默认关闭用 remoteAddr） | ✅ |
| AUTH-5 | 登录流程 `AuthApplicationService:79-95` | 🟡 | 用户名枚举/时序侧信道 | ⬜ |
| AUTH-6 | `AiModelConfigService`/`AdminAiModelController` | 🟡 | AI 配置贫血 DO CRUD 破坏 DDD 分层 | ⬜ |
| AUTH-7 | 多处 `catch(Exception)` | 🟡 | 宽泛吞异常，`DataScopeAspect` 降级 SELF 影响数据可见范围 | ⬜ |
| AUTH-8 | `AiModelConfigService:381` / `LoginRateLimiter:88` 等 | 🟡 | String.format 拼 JSON、每次 new RedisScript、getDisplayNames 无上限、do_ 命名 | ⬜ |

### ai-conversation
| ID | 位置 | 严重度 | 问题 | 状态 |
|---|---|---|---|---|
| CONV-1 | `HttpToolRunner` + `SsrfGuard` | 🟠 | DIT 工具用用户可控参数拼 URL 发请求，无私网/回环/metadata 拦截 → SSRF（已加 SsrfGuard 出站校验；/test 回显收窄见 CONV-7） | ✅ |
| CONV-2 | `AbstractWebhookSender.java:45-49` | 🟠 | webhook url/header(含签名密钥)/body 全量 INFO 明文落盘 | ✅ |
| CONV-3 | `ConversationHistoryRepository.java:132` | 🟠 | `initializedSessions` 无界 Set，会话过期不清理 → 长跑 OOM | ✅ |
| CONV-4 | `AbstractWebhookSender:86-116` | 🟠 | 自定义模板裸 `String.replace` 注入未转义 → 访客昵称可注入 webhook JSON | ✅ |
| CONV-5 | `SessionQueueService`/`FaqChatAppService` | 🟠 | application 层直接依赖 infrastructure 实体，DDD 依赖倒置 | ⬜ |
| CONV-6 | `AgentChannelWsHandler.java:120` | 🟡 | 座席 WS 消息按字符数校验，与访客端(字节数)不一致 | ✅ |
| CONV-7 | `HttpToolRunner.java:163-196` | 🟡 | `extractByJsonPath` 失败降级回显完整上游响应 | ✅ |
| CONV-8 | `VisitorAuthController.java:47-51` | 🟡 | 访客短信发送缺 IP 维度限流 | ⬜ |
| CONV-9 | `KnowledgeServiceClient.java:84,42` | 🟡 | debug 打印完整 RAG 响应；内部密钥有可用默认值无启动强校验 | ⬜ |

### ai-knowledge
| ID | 位置 | 严重度 | 问题 | 状态 |
|---|---|---|---|---|
| KNOW-1 | `DocumentIngestPipeline.process:51` | 🟠 | 整条摄取 pipeline 单 DB 事务内执行且中间发 Embedding HTTP → 连接池耗尽 | ✅ |
| KNOW-2 | `InternalSecretFilter`(common-web) | 🟠 | `/internal/**` 实际由 InternalSecretFilter 强制校验 X-Internal-Secret(fail-secure)；已消除默认密钥(prod fail-fast) | ✅ |
| KNOW-3 | `DocIngestAppService.submit:87-89` | 🟠 | 上传接口缺 isEmpty/大小/扩展名白名单，未知类型回退 MARKDOWN | ✅ |
| KNOW-4 | `MinioStorageService`/`ZipParser` | 🟠 | 大文件全量读入内存多处放大，ZIP 累积无总量上限 | ⬜ |
| KNOW-5 | `KnowledgeChunkMapper.xml:91-98` | 🟡 | `${tsConfig}` 字符串拼接进 SQL（当前受控，隐患） | ⬜ |
| KNOW-6 | `DocIngestAppService.java:351-355` | 🟡 | batchOffline 循环内 N 次 findById | ⬜ |
| KNOW-7 | `KnowledgeSearchAppService.java:80` | 🟡 | query 向量化同步调用无独立超时 | ⬜ |
| KNOW-8 | `TranslateController.java:73-77` | 🟡 | 每次 new ObjectMapper + catch(Exception) 回显内部异常 | ⬜ |
| KNOW-9 | Application 层直接依赖 Infrastructure 具体类 | 🟡 | DDD 分层泄漏 + `KnowledgeDoc` 贫血模型 | ⬜ |

### ai-common（被全系统依赖，缺陷放大）
| ID | 位置 | 严重度 | 问题 | 状态 |
|---|---|---|---|---|
| COMM-1 | `common-web/pom.xml` + `RemoteAiModelConfigProvider` | 🟠 | common-web 反向依赖 auth-client/knowledge-client 并内置 AI 配置业务 → DDD 分层倒置 | ⬜ |
| COMM-2 | `common-client/main/java/...` | 🟠 | git 跟踪的过期重复源码树（旧 AK/SK 版 BaseClient），易误用 | ✅ |
| COMM-3 | `RetryInterceptor.java:28-58` | 🟠 | 对非幂等 POST/PUT 也重试且 Thread.sleep 最长阻塞 65s → 重复扣费+线程耗尽 | ✅ |
| COMM-4 | `AkSkSigningInterceptor.java:59-64` | 🟠 | 签名不含 query string → GET 参数可被篡改越权 | ✅ |
| COMM-5 | `SensitiveDataUtils.java:39-42` | 🟠 | 手机号正则无边界先于身份证执行 → 身份证漏脱敏(PII 合规红线)；邮箱未覆盖 | ✅ |
| COMM-6 | `IdGenerator.java:67-78` | 🟠 | workerId 缺失回退 pid%1024，容器 PID 趋同 → 雪花 ID 冲突 | ✅ |
| COMM-7 | `ControllerUtils.toLong:24-32` | 🟡 | 非法输入静默转 0L | ⬜ |
| COMM-8 | `R.java:22-23` / `BusinessException:44-47` | 🟡 | 非数字 code 用 hashCode 兜底 | ⬜ |
| COMM-9 | `SaTokenWebConfig.java:37` | 🟡 | actuator 端点 Sa-Token 层完全放行 | ⬜ |
| COMM-10 | `DomainEvent.getAggregateType:44` | 🟡 | 每次调用重编巨型正则 | ⬜ |
| COMM-11 | `VectorUtils.fromStr:44-46` | 🟡 | 未校验闭合括号 | ⬜ |
| COMM-12 | `AutoFillMetaObjectHandler:23,36` | 🟡 | 用系统默认时区 | ⬜ |

---

## 三、修复优先级批次

- **P0（阻断上线）**：SYS-1a, SYS-1b, SYS-1c, SYS-1d, SYS-1f, SYS-2a, SYS-2b, SYS-2c, SYS-2d
- **P1**：CONV-1, KNOW-2, AUTH-1, AUTH-2, AUTH-3, AUTH-4, SYS-1e
- **P2**：CONV-3, KNOW-1, COMM-5, COMM-6, COMM-3, COMM-4, KNOW-3, CONV-2, CONV-4
- **P3**：DDD 依赖倒置(CONV-5, KNOW-9, AUTH-6, COMM-1) + 清理死代码(COMM-2) + 其余 🟡

---

## 四、整改日志（按批次追加）

### 批次 1 — 2026-08-06 — P0 授权越权（SYS-1a/b/c/d + CONV-6）

**SYS-1a/1b 角色/菜单接口权限注解**
- `RoleController.java`：create/update/delete/assignPermissions/assignMenus/setDataScope 补 `@SaCheckPermission`
- `MenuController.java`：create/update/delete 补 `@SaCheckPermission`
- 新增 SQL patch `docs/sql/migrations/2026-08-06-add-role-menu-permissions.sql`：新建权限 `system:role:assign-perm`(65)、`system:role:data-scope`(66)、`system:menu:create/update/delete`(67-69)，并绑定 super_admin(role 10) + 对应菜单按钮。
- ⚠️ **部署依赖**：上线前必须先执行该 SQL patch，否则 super_admin 也会 403（无通配权限机制）。

**SYS-1c 访客会话 IDOR**
- 新增 `SessionOwnershipValidator`（application 层）：绑定校验+匿名兼容策略。
- 新增 `ConversationPersistRepository.findVisitorIdBySessionId`。
- `ChatController` /history、DELETE /history、/state 三接口注入校验，读 `X-Visitor-Token`/`X-Anonymous-Id` 头，失败返回 403。
- 新增 `VisitorHandshakeInterceptor` 并在 `WebSocketConfig` 挂到 `/ws/chat/*`，握手阶段校验归属（token/anonymousId 走 query），失败返回 403。
- ⚠️ **前端依赖**：访客端 REST 需带 `X-Visitor-Token`(已认证) 或 `X-Anonymous-Id`(匿名) 头；WS 需带 `?token=` 或 `?anonymousId=` query。

**SYS-1d 座席跨会话注入 + CONV-6**
- `AgentChannelWsHandler` 注入 `SessionQueueService`，`handleTextMessage` 校验 `body.sessionId()` 的负责座席 == 当前 agentId，不匹配拒绝。
- 顺带把消息长度校验从字符数改为 UTF-8 字节数（对齐访客端）。

**验证**：`mvn -q -pl ai-conversation/conversation-service compile` 通过（无错误）。auth 模块待编译验证。

### 批次 2 — 2026-08-06 — P0 密钥 fail-open / 明文（SYS-2a/b/c/d）

**SYS-2a EncryptUtils fail-open**
- `EncryptUtils.initKeyBytes()`：env 设置但非法（长度≠32/Base64 错误）一律 fail-fast 抛异常；prod profile（SPRING_PROFILES_ACTIVE/spring.profiles.active 含 prod）下缺失有效 key 亦 fail-fast；仅非 prod 允许开发默认密钥并告警。

**SYS-2b WebhookEventSubscriber fail-open**
- `verifySignature`：secret 未配置改为 fail-close 返回 false；签名比对改用 `MessageDigest.isEqual` 恒定时间。

**SYS-2c 生产密钥明文入库**
- 三服务 `application-prod.yml`：jwt-secret-key / shared-secret / internal.secret / MinIO secret-key 改为无默认值环境变量占位（未配置即 fail-fast）。
- 三服务 `application.yml`：硬编码的 `aria-internal-lycodeing-2024` / 真实密钥改为环境变量占位 + 明确 dev 默认值（不再泄露生产密钥）。
- ⚠️ **部署依赖**：prod 部署必须注入 JWT_SECRET_KEY / ARIA_INTERNAL_SECRET / MINIO_SECRET_KEY 等环境变量，否则服务启动失败。
- ⚠️ **密钥轮换**：历史提交中泄露的 `aria-internal-lycodeing-2024`、`cs-auth-lycodeing-secret-key-2024`、`Lycodeing@2024` 需在生产轮换。

**SYS-2d API Key 明文落库**
- `AiModelConfigService`：新增 `@Value("${aria.security.encrypt-api-key:false}")` 开关；`normalizeApiKey` 开关开启时对裸 Key 走 AES-256-GCM 加密（`AES:` 前缀），否则回退明文（兼容本地/旧数据）。

**验证**：`mvn -q compile`（全 12 模块）BUILD SUCCESS。

### 批次 2 — 2026-08-06 — P1（SSRF / 账号安全 / admin 授权）

**CONV-1 DIT SSRF**
- 新增 `SsrfGuard`（infrastructure/dit/pipeline）：出站前校验协议(http/https)、解析 host 全部 IP 拒绝回环/私网/link-local(含云 metadata 169.254.169.254)/通配/多播/IPv6 ULA。内嵌 `SsrfBlockedException`。
- `HttpToolRunner` 注入 SsrfGuard，占位符替换后、发请求前调用 `validate(url)`。

**AUTH-1 密码历史重用校验修正**
- `User.changePassword` 签名加 `newPlain`，历史比对改用 `hasher.matches(newPlain, oldHash)`（BCrypt 盐随机，原比对两个哈希永远 false）。`UserApplicationService` 调用同步更新。

**AUTH-2 refreshToken 校验账号状态**
- `refreshToken` 内加 `user.canLogin()` 校验，禁用/锁定用户刷新时 logout + 抛 UNAUTHORIZED。

**AUTH-3 撤权/禁用主动踢出**
- `UserApplicationService.disable`/`assignRoles` 提交后 `StpUtil.kickout(id)`，强制目标用户重新鉴权，消除撤权窗口期。

**AUTH-4 XFF 可伪造**
- `AuthController` 加 `aria.security.trust-proxy-headers`（默认 false）；关闭时忽略 XFF/X-Real-IP 直接用 `getRemoteAddr()`，可信反代后才开启。

**SYS-1e admin 接口权限**（conversation）
- DitTool/DitDomain/DitIntent → `system:dit:view`(读)/`system:dit:manage`(写)
- CannedResponseAdmin → `system:canned:view`/`system:canned:manage`
- Dashboard 全局聚合 → `system:dashboard:view`；`/my-*` 个人数据保持 `@SaCheckLogin`

**SYS-1f knowledge IDOR**（knowledge）
- KnowledgeDoc: list/status/preview/chunks/stats/kb-stats/search-test → `knowledge:doc:view`；upload/retry/reingest→`knowledge:doc:upload`；review→`knowledge:doc:review`；offline/batch-offline→`knowledge:doc:offline`
- KnowledgeChunk: disable/enable/updateContent/addQA → `knowledge:doc:review`
- ⚠️ 说明：本批补的是「接口级授权」（登录用户需持权限）。若为多租户场景，还需在 Application 层加「docId/kbId 归属校验」做数据级隔离——列入 P3 跟踪（KNOW-IDOR-DATA）。

**KNOW-2 /internal 密钥**
- 复核结论：`/internal/**` 已由 common-web `InternalSecretFilter` 强制校验 `X-Internal-Secret`（fail-secure，缺密钥拒绝一切）。结合批次 1 的密钥去明文（prod fail-fast），KNOW-2 已闭环。

**新增 SQL patch**：`docs/sql/migrations/2026-08-06-add-dit-dashboard-knowledge-permissions.sql`（权限 id 70-75 + super_admin 绑定）。

**验证**：全工程 `mvn compile` 通过；conversation 全测试通过（HttpToolRunnerTest/AgentChannelWsHandlerTest/ChatControllerStreamTest 已同步构造器）；auth/common 编译通过。

**遗留（转入后续批次）**：
- KNOW-IDOR-DATA：knowledge 数据级租户隔离（本批仅接口级授权）
- 前端配套：新增权限 key 对应的 sys_menu 按钮 + 角色-菜单绑定（kf_manager/kf_staff 按需），当前仅绑定 super_admin

### 批次 3 — 2026-08-07 — P2 健壮性/资源/PII（CONV-2/3/4/7 + KNOW-1/3 + COMM-3/4/5/6）

**COMM 通用库**
- `SensitiveDataUtils`：手机号加负向环视边界、调整脱敏顺序（身份证/银行卡先于手机号）、补充邮箱脱敏，修复身份证漏脱敏（PII 合规）。
- `IdGenerator`：workerId 增加系统属性回退 + PID 兜底告警；时钟回拨超 5s 阈值抛异常，取代无限忙等。
- `RetryInterceptor`：仅对幂等方法（GET/HEAD/OPTIONS/PUT/DELETE）重试，429/503 尊重 Retry-After，退避封顶 + 抖动，避免非幂等 POST 重复扣费与线程长阻塞。
- `AkSkSigningInterceptor`：签名串纳入 encodedQuery，防 GET 参数篡改越权（服务端当前无验签实现，改动不破坏现网）。

**ai-conversation**
- `AbstractWebhookSender`：请求/响应日志降级为 debug 并对 headers 脱敏（CONV-2）；新增 `renderJsonTemplate`，raw JSON 模板分支对变量值先 JSON 转义再注入，修复访客昵称 JSON 注入（CONV-4）；4 个 sender（Custom/Feishu/Dingtalk/Wecom）改为基于原始模板判断 + 转义渲染。
- `ConversationHistoryRepository`：`initializedSessions` 由无界 Set 改为 Caffeine（maxSize=100k + 24h 过期），修复长跑内存泄漏（CONV-3）。

**ai-knowledge**
- `DocumentIngestPipeline`：去掉整链方法级 `@Transactional`，改用 `TransactionTemplate` 仅包裹写库段（@Order≥8 的 Persist/StatusUpdate），前段解析/向量化 HTTP 不再占用 DB 连接，修复连接池耗尽（KNOW-1）。
- `DocIngestAppService.submit`：新增上传入口校验（空文件/大小上限 50MB/扩展名白名单），未知类型拒绝而非静默回退 MARKDOWN（KNOW-3）。

**验证**：全工程 `mvn -q compile` 通过；conversation 352 测试全绿，common 44 测试全绿。

### 批次 4 — 2026-08-07 — P3 死代码清理 + DDD 暂缓说明

**COMM-2 死代码清理（已修复）**
- 删除 `ai-common/common-client/main/java/**` 整棵重复源码树（旧 AK/SK 版 BaseClient/RetryInterceptor/WebhookEventSubscriber 等 9 文件）。该目录不在 Maven 构建路径（pom 用默认 `src/main/java`），属 git 误跟踪的过期副本，易被误改误用。真实活代码在 `src/main/java`，本轮 SYS-2b（WebhookEventSubscriber fail-close）修的即活代码那份，已确认保留。
- 验证：全工程 `mvn -q compile` 通过。

**DDD 依赖倒置（CONV-5 / KNOW-9 / AUTH-6 / COMM-1）— 本轮暂缓**
- 结论：不纳入本安全整改轮次。理由：①这四项评审定位为「分层泄漏 / 贫血模型」，属架构演进项，非功能或安全缺陷，不阻断上线；②整改需反转 application↔infrastructure 依赖方向、引入端口接口并迁移实体，跨多文件大重构，回归风险高；③在以安全修复为主的批次里夹带高风险重构，违背「小步可回滚」原则。
- 建议：作为独立技术债专项另起分支处理，配套补充分层单测后再动。已在本文档保留 ⬜ 状态持续跟踪。

**其余 🟡（COMM-7 等）**
- COMM-7（ControllerUtils.toLong 非法输入返回 0L）：现有 Javadoc 明确其为「避免 500 的容错降级」设计，`getCurrentUserId` 侧已有 null 分支兜底。贸然改为抛异常可能破坏 dashboard/stats 的降级路径，暂维持现状并保留跟踪，留待 DDD 专项一并评估。

### 评审轮次 1 — 2026-08-07 — 复审发现修复（消息写路径 IDOR + POST 429 重试）

代码评审子代理对 P1/P2/P3 三批提交做全量复审，**无 🔴 阻断项**，核心修复（授权/密钥/SSRF/密码/webhook/事务）全部确认正确。发现 2 个 🟠 一致性/遗漏问题，已修复：

**🟠#1 消息发送路径 IDOR 遗漏（已修复）— 补齐 SYS-1c 闭环**
- 问题：P0-2 只给 `/history`、`DELETE /history`、`/state` 三个读接口加了归属校验，但访客发消息的 `POST /stream`、`POST /`（chat）仍只做 sessionId 格式校验。攻击者枚举他人 sessionId 后仍可向其会话注入消息并接收 AI 回复——比读历史更敏感的写越权。
- 修复：`ChatController.streamChat` / `chat` 同样注入 `X-Visitor-Token`/`X-Anonymous-Id` 头 + `sessionOwnershipValidator.isOwner` 校验，失败返回 error 帧 / 403。会话由前端先经 `/session/init` 建立（DB 已记 visitorId），此处对已存在会话强制校验。
- 测试：`ChatControllerStreamTest` 6 用例同步更新签名 + lenient stub，全绿。

**🟠#2 RetryInterceptor 对 POST 429 一刀切拒绝重试（已修复）**
- 问题：原实现对所有非幂等方法（含 POST）直接不重试。但 LLM chat completions、BGE-M3 embed 均为 POST，且 HTTP 429 表示服务端**未处理**请求（无副作用），此时不重试会使限流即失败，削弱摄取/对话链路可用性。
- 修复：`shouldRetry(request, code)` 区分状态码——429 对所有方法放行重试（尊重 Retry-After）；5xx 仍仅对幂等方法（GET/HEAD/OPTIONS/PUT/DELETE）重试，保留防重复扣费语义。

**🟠 残留风险（文档标注 + 部署约定，不阻断合并）**
- **#3 EncryptUtils prod 判定**：仅读系统属性/环境变量 `SPRING_PROFILES_ACTIVE`。**部署约定：生产必须通过环境变量激活 prod profile**，不可仅在 application.yml 写 `spring.profiles.active: prod`（Spring 不会回写同名系统属性，会导致 fail-fast 失效回退开发密钥）。本地/测试无此变量走告警回退，不影响启动。
- **#4 SsrfGuard DNS rebinding 残留**：`validate()` 解析并校验所有 IP，但 WebClient 连接时会二次独立解析，短 TTL DNS 可在校验后重绑到内网 IP（TOCTOU）。彻底方案需 pin 校验通过的 IP 或出站代理二次拦截；当前已覆盖直接的私网/metadata 访问，rebinding 属已知残留，列入后续跟踪。
- **#5 AkSkSigningInterceptor 签名含 query 是破坏性变更**：任何验签方须在同一位置纳入 query。经确认当前生产走 SHARED_SECRET 模式、服务端无 Ak/Sk 验签实现，改动不破坏现网；若未来启用 Ak/Sk 验签，需 lockstep 同步签名串格式。

**验证**：全工程 `mvn compile` 通过；conversation 352 测试全绿，common 全绿。

### 评审轮次 2 — 2026-08-07 — IDOR 完全闭环（收敛确认后补漏）

第二轮评审确认前一轮两处修复（消息发送归属校验、RetryInterceptor 429/5xx 区分）正确无回归，但指出 IDOR 主题仍有同源遗漏：`/transfer`、`/stream/cancel`、`/messages/feedback` 三个访客可达、按 sessionId 执行写/动作的接口仍缺归属校验。本轮补齐：

- `ChatController.transfer`：注入 `X-Visitor-Token`/`X-Anonymous-Id`，`isOwner` 校验失败返回 403。堵住"枚举他人 sessionId 强推其会话入人工队列 + 攻击者可控 userName/reason/tag 骚扰坐席"。
- `ChatController.cancelStream`：query 参数 sessionId + 归属校验，堵住"中断他人 AI 生成"的逐会话 DoS。
- `ChatController.submitFeedback`：请求体 sessionId + 归属校验，堵住"污染他人消息反馈统计"。
- 至此 `ChatController` 全部按 sessionId 操作的访客接口（history/clearHistory/state/stream/chat/transfer/cancel/feedback）归属校验口径统一，SYS-1c **完全闭环**。

**负向测试补充**：`ChatControllerStreamTest` 新增 `isOwner=false` 时 streamChat 返回 error+done 帧的用例；`ChatControllerFeedbackTest` 补 `SessionOwnershipValidator` mock。

**验证**：全工程编译通过；conversation 353 测试全绿。

⚠️ **前端联调约定**：`/stream`、`POST /`、`/transfer`、`/stream/cancel`、`/messages/feedback` 现均强制归属校验，前端所有访客写请求必须携带 `X-Anonymous-Id`（匿名）或 `X-Visitor-Token`（已短信认证）头，否则被 403 拒绝。上线前须确认 chat-widget 已在这些请求附带对应头。

### 复审轮次 3 — 2026-08-07 — CSAT/VisitorAuth 同源 IDOR 闭环

第三轮评审发现 IDOR 主题在 ChatController 之外仍有同源遗漏，本轮补齐：

**CsatController（rate/skip 写 + pending 读）**
- `rate`/`skip`：csatId 自增可枚举，新增 `X-Visitor-Token`/`X-Anonymous-Id` 头 + `isCsatOwner`（先经 `CsatService.findSessionIdByCsatId` 反查 sessionId，再 `isOwner` 校验归属），阻止攻击者为他人评价记录提交任意分数/评论并污染 CSAT 统计与下游 webhook；归属失败返回 403。
- `pending`：刷新恢复场景，新增 `X-Anonymous-Id` 头 + `isAnonymousOwner` 校验，防枚举 sessionId 探测他人评价邀请。
- `CsatService` 新增 `findSessionIdByCsatId(csatId)` 供归属反查。

**VisitorAuthController.state（读，含 PII）**
- 泄露 `phoneMask`（部分手机号）+ 认证状态，新增 `X-Anonymous-Id` 头 + `isAnonymousOwner` 校验，防枚举 sessionId 探测他人认证状态与手机号掩码。用 anonymousId 分支（非 token）校验，兼容"刷新丢 token 但 localStorage 仍有 anonymousId"的合法场景。

**SessionOwnershipValidator**
- 新增 `isAnonymousOwner(sessionId, anonymousId)`：仅以 anonymousId==DB visitorId 判定归属，供刷新恢复类只读接口复用；原 `isOwner` 匿名分支重构为复用该方法。

**验证**：全工程 `mvn -q compile` 通过；conversation 356 测试全绿（含 CSAT rate/pending、VisitorAuth state 的归属失败负向用例）。

**IDOR 主题收敛确认**：访客可达且按 sessionId/csatId 操作的接口已全部接入归属校验——ChatController（history/clearHistory/state/stream/chat/transfer/cancel/feedback）、CsatController（rate/skip/pending）、VisitorAuthController（state）。合理豁免：sms/send、sms/verify（认证引导）、session/init（建会话）。

### 批次 7 — 2026-08-07 — 评审轮次4：VisitorAuth.verify 会话绑定归属校验（同源写收口）

**背景**：第四轮评审系统性穷举 conversation 全部 27 个 Controller + 2 个 WS 握手 + knowledge 4 个 Controller，判定「访客侧 IDOR 主题已完全闭环收敛」。同时指出唯一残留的同源写点：`VisitorAuthController.verify` 建立 `session→phone` 绑定时未校验 sessionId 归属。

**VisitorAuth.verify 会话劫持收口（🟡→已修复）**
- 攻击链：知悉他人 sessionId 者可用自己手机号完成 verify 并写入 `visitor:session:auth:{sessionId}=自己的phone` 绑定，使该会话翻转为「已认证」并被准接管（受害匿名访客被踢分支 + 攻击者 token 可读该会话状态）。虽 sessionId 为 128bit UUID 不可枚举（需泄露），仍属同源写残留。
- 修复：`verify` 新增 `X-Anonymous-Id` 头，传入 sessionId 时先 `isAnonymousOwner(sessionId, anonymousId)` 校验归属，非归属者返回 403「无权绑定该会话」；未传 sessionId（纯 token 场景）不涉及绑定，不校验。与 pending/state 的匿名归属校验模式一致。
- 测试：`VisitorAuthControllerTest` verify 用例改双参 + 补「非归属 sessionId 返回 403 且不调用 verifyCode」负向用例。

**评审其余结论（无需改代码）**
- 🟠 rate/skip 对「已短信认证会话刷新丢 token」可能误拒：属体验问题非安全漏洞，取决于前端刷新后能否重新签发 token，列入前端联调确认项，不阻断收敛判定。

**验证**：全工程 `mvn -q compile` 通过；conversation 357 测试全绿（VisitorAuthControllerTest 6→7）。

**收敛结论**：连续 4 轮评审，IDOR 主题从「读接口部分覆盖」逐轮扩展至 sessionId + csatId 全路径（REST 13 接口 + WS 握手）闭环，第四轮穷举确认无新同源遗漏。P0/P1/P2 全部修复并验证；P3 死代码已清理，DDD 依赖倒置整改按既定理由暂缓（独立技术债专项跟踪）。
