# ARIA-Server 全量代码评审报告

> 评审日期：2026-07-30
> 评审范围：ai-conversation / ai-auth / ai-knowledge / ai-common 四个模块，共 634 个 Java 文件
> 重点类别：安全、并发、事务、SQL/MyBatis、资源泄漏、逻辑错误
> 说明：以下高危问题均已通过阅读源码核实；中低危问题为静态排查结论，修复前建议再复核。

## 严重程度汇总

| 级别 | 数量 | 编号 |
| --- | --- | --- |
| 🔴 高危 | 5 | H1 ~ H5 |
| 🟡 中危 | 9 | M1 ~ M9 |
| 🟢 低危 | 6 | L1 ~ L6 |

---

## 🔴 高危问题（建议立即修复）

### H1. 任意登录用户可重置他人密码 / 删除用户（垂直越权）
- **文件**：`ai-auth/auth-service/.../interfaces/rest/UserController.java` L66-L125
- **问题**：类级仅有 `@SaCheckLogin`，全类只有 `assignRoles`（L127）加了 `@SaCheckRole("admin")`。而 `POST /{id}/reset-password`（L120）、`DELETE /{id}`（L106）、`POST /{id}/disable|enable`（L94-L104）、`PUT /{id}`（L87）、`POST /`（L66）等管理端接口**均无角色/权限校验**。
- **影响**：任意已登录的普通用户即可重置他人密码实现账户接管、删除/禁用任意用户。
- **修复**：为所有管理端接口补 `@SaCheckRole("admin")` 或对应权限注解；`/me`、`/{id}/change-password` 等自操作接口需校验 `id == 当前登录用户`。

### H2. SLA 自动升级永不触发（字符串与枚举比较恒为 false）
- **文件**：`ai-conversation/.../infrastructure/scheduler/SlaBreachNotifier.java` L100-L106
- **问题**：`SlaBreachEntity.stage` 字段类型是 `BreachStage` 枚举，代码用 `BreachStage.BREACH.name().equals(b.getStage())` —— `String.equals(枚举对象)` 恒返回 false。
- **影响**：`hasActualBreach` 恒为 false，即使配置了 `autoEscalate`，真实 BREACH 违约也永远不会升级，功能静默失效。
- **修复**：改为 `BreachStage.BREACH == b.getStage()` 或 `b.getStage() == BreachStage.BREACH`。

### H3. 节假日自动同步静默失效（同步年份与过滤窗口矛盾）
- **文件**：`ai-conversation/.../infrastructure/scheduler/HolidaySyncScheduler.java` L56-L95
- **问题**：cron 每 3 个月执行（`0 0 0 1 */3 *`），方法拉取「明年」数据传入 `syncYear`，但 `syncYear` 内部过滤窗口是「今天起 3 个月」。1/4/7 月执行时窗口完全落在当年，明年数据被全量过滤，写入 0 条；而当年节假日从无任何路径拉取。L83 跨年合并分支在目标年恒为明年时永远为假（死代码）。
- **影响**：法定节假日几乎不会被自动同步，工作时间/SLA 判定把节假日当工作日处理。
- **修复**：同步「当前年 + 未来窗口所覆盖的年份」，使拉取年份与过滤窗口对齐。

### H4. SSO Cookie 属性拼写错误，浏览器不识别（Cookie 立即失效 / 无法清除）
- **文件**：`ai-auth/auth-service/.../infrastructure/auth/SsoCookieWriter.java` L47-L56
- **问题**：手动拼接 Set-Cookie 头时用了 `MaxAge=`（L51），但 HTTP Cookie 标准属性是 `Max-Age`（带连字符）。拼错的属性会被浏览器忽略，Cookie 退化为会话级；`clearTokenCookie` 想用 `MaxAge=0` 清除也无效。
- **影响**：登录态持久化行为异常，登出/刷新时 Cookie 可能无法按预期清除或保留。
- **修复**：`MaxAge` → `Max-Age`。

### H5. 身份证脱敏正则永不匹配，PII 原样进入向量库
- **文件**：`ai-common/common-core/.../util/SensitiveDataUtils.java` L16-L17
- **问题**：`ID_CARD_PATTERN = (\d{6})\d{8}(\d{4}[Xx])` 要求 18 位数字后再跟一个强制的 X（共 19 位）。合法 18 位身份证是 17 位数字 + 1 位校验码（数字或 X），因此该正则对任何合法身份证都不匹配。
- **影响**：历史工单入库清洗时身份证号原样写入 chunk 并被向量化，可经知识检索召回泄漏（PII 合规风险）。
- **修复**：改为 `(\d{6})\d{8}(\d{3}[0-9Xx])`，即前 6 + 中 8 + 后 3 位数字 + 末位（数字或 X）。

---

## 🟡 中危问题

### M1. 转人工入队无状态防护，可把 ACTIVE 会话覆盖回 WAITING
- **文件**：`SessionQueueService.java` L108-L132 + `SessionQueueRepository.save` L60-L67（无条件覆盖写）
- **问题**：`enqueue` 直接 new WAITING 项并覆盖写 Redis，不检查是否已在队列、不走状态机。结合 `ChatAppService.stream` 入口 `isActive` 检查与后续意图分类之间的时间窗（TOCTOU），若座席恰好在此期间 `accept`，入队会把 ACTIVE 项整体覆盖为 WAITING 且 agentId 置空，导致访客消息无法路由到座席。
- **修复**：入队前做存在性/状态校验（仅允许 AI_CHAT/无记录 → WAITING）。

### M2. `SessionQueueService.close` catch 范围过宽吞异常，DB 关闭被跳过
- **文件**：`SessionQueueService.java` L288-L318
- **问题**：整个 close 流程（含 `publishSessionEnd` DB 落库、Redis delete、triggerCsat）被同一 `catch(IllegalStateException)` 包裹，而 `save`/CAS 序列化失败正是抛 IllegalStateException。任何一步抛出都会跳过 DB 关闭，与「无论如何都执行 DB 关闭」的设计矛盾，且仅记 warn。
- **修复**：把状态机校验与后续副作用分离，DB 关闭放在 catch 之外保证执行。

### M3. FAQ 转人工入队失败仍告知用户「已转接」
- **文件**：`FaqChatAppService.java` L123-L147
- **问题**：`enqueue` 抛一般异常（含 Redis 入队失败）后仅 warn，随后照常写「已转接人工」历史并发 TRANSFER 事件。访客以为已转接并等待，但队列里根本没有此会话，座席永远看不到。
- **修复**：入队失败时给出明确失败提示，不发 TRANSFER 事件。

### M4. AiSummary SSE 订阅未随连接释放（资源泄漏）
- **文件**：`AiSummaryService.java` L79-L162
- **问题**：`tokenFlux.subscribe(...)` 丢弃了 Disposable，未注册 onTimeout/断开时 dispose。SSE 超时或客户端断开后 LLM 流仍在后台消耗配额/线程；token 回调只 catch IOException，emitter complete 后再 send 抛的 IllegalStateException 会逸出。
- **修复**：保存 Disposable，在 `emitter.onCompletion/onTimeout/onError` 中 dispose。

### M5. SDK 拦截器顺序错误，重试复用同一 nonce/签名必然失败
- **文件**：`ai-common/common-client/.../sdk/BaseClient.java` L41-L48
- **问题**：签名拦截器在外层、重试拦截器在内层（后添加的先内层执行），重试重放的是已签名请求，X-Timestamp/X-Nonce/X-Signature 不变。服务端一旦启用 nonce 防重放校验，所有重试都会被判为重放而拒绝（401/403 又不在重试白名单），指数退避形同虚设。
- **修复**：把 RetryInterceptor 放在签名拦截器之前（外层），使每次重试重新生成时间戳/nonce 并重签。

### M6. DitManageAppService 事务提交前失效缓存（旧值回填）
- **文件**：`DitManageAppService.java` L92-L113、L164-L172
- **问题**：`evict` 在 `@Transactional` 方法体内、提交前执行。并发读在 evict 之后、提交之前 miss，会用 DB 旧值回填缓存，提交后缓存留脏数据，意图路由长时间按旧配置执行。
- **修复**：改用 `TransactionSynchronizationManager` 的 afterCommit 回调 evict。

### M7. PersistHandler 分布式锁在事务提交前释放（并发产生双份 chunk）
- **文件**：`ai-knowledge/.../infrastructure/mq/handler/PersistHandler.java` L58-L67 + `DocumentIngestPipeline.java` L51（整链单事务）
- **问题**：锁在责任链步骤内 finally 释放，但外层事务此时未提交。MQ 重复投递时消费者 A 释放锁（插入未提交）后 B 获锁，读已提交隔离级别下删不掉 A 未提交的数据，最终同一 docId 出现两套 chunk。
- **修复**：锁持有到事务提交之后（事务同步回调中释放）。

### M8. Dashboard 统计 NULL 标签会话双双遗漏
- **文件**：`ai-conversation/.../resources/mapper/DashboardStatsMapper.xml` L56-L57、L169-L170
- **问题**：`COUNT(*) FILTER (WHERE tag != 'AI 对话')` 在 SQL 三值逻辑下 NULL 比较结果为 NULL，tag 为 NULL 的会话既不计入 humanCount 也不计入 aiCount（同文件 L85 用 COALESCE 证明 tag 可为 NULL）。
- **修复**：humanCount 条件改为 `tag IS DISTINCT FROM 'AI 对话'` 或 `COALESCE(tag,'') != 'AI 对话'`。

### M9. 递归切分器对「含分隔符但单片仍超长」的片段不再下钻
- **文件**：`ai-knowledge/.../infrastructure/splitter/RecursiveChunkSplitter.java` L123-L163
- **问题**：仅当当前分隔符完全不出现（parts<=1）时才递归下一层。若文本含当前层分隔符但某单片自身超长，该单片会被原样提交为最终 chunk，产出超过 maxTokens 的块，可能超 embedding 输入上限。
- **修复**：对累积/提交的单片再次递归 `splitRecursive(part, separatorIdx+1)`。

---

## 🟢 低危问题

- **L1. CSAT 幂等 check-then-insert 竞态** — `CsatService.java` L39-L56：并发关闭路径可能各插一条，建议依赖 session_id 唯一约束 + 幂等 upsert。
- **L2. triggerCsat 对 agentId 做 Long.parseLong** — `SessionQueueService.java` L446-L460：座席 ID 允许字母/连字符，非数字会抛异常被吞，CSAT 邀请丢失。
- **L3. 验证码锁定可被「重发」绕过** — `VisitorAuthService.java` L66-L81：`sendCode` 无条件 `resetAttempts`，攻击者每 60s 重发即清空错误计数，10 分钟锁定形同虚设。
- **L4. TagAppService 事务提交前发布 SSE 事件** — L195-L206：回滚时前端收到幻影通知（代码 NOTE 已自认）。
- **L5. WebSocket 消息长度限制混淆字符/字节** — `ChatWebSocketHandler.java` L61-L156：`getPayloadLength()` 返回字符数，中文场景实际上限约为设计值 3 倍。
- **L6. SessionQueueController closedLimit 无上限校验** — L104-L108：注释称最大 200，实际无 Max 校验，可传超大值导致慢查询。

另有：Webhook 签名校验用普通字符串比较（非恒定时间）且未配 secret 时直接放行；`KnowledgeSearchAppService` L108 `toMap` 无合并函数遇重复 chunkId 会抛异常；`DocIngestPublisher.recover` 在 afterCommit 语境写库可能不落库（建议 REQUIRES_NEW）；common-client 存在 `main/java` 与 `src/main/java` 两套源码目录（工程卫生）。

---

## 修复状态（2026-07-30）

> 全部 20 项（H1~H5 / M1~M9 / L1~L6）已修复，受影响的 6 个模块 `mvn compile` 通过（BUILD SUCCESS）。

| 编号 | 状态 | 修复要点 |
| --- | --- | --- |
| H1 | ✅ | UserController 管理端接口补 `@SaCheckRole("admin")`；change-password 增加自操作归属校验 |
| H2 | ✅ | `BreachStage.BREACH == b.getStage()` 枚举直接比较 |
| H3 | ✅ | 同步起始年改为当前年（`syncUpcomingHolidays`），与「今天起 3 个月」窗口对齐 |
| H4 | ✅ | `MaxAge` → `Max-Age` |
| H5 | ✅ | 身份证正则改为 `(\d{6})\d{8}(\d{3}[0-9Xx])` |
| M1 | ✅ | 新增 `hPutIfAbsent`/`saveIfAbsent`（HSETNX 语义），enqueue 原子入队防覆盖 |
| M2 | ✅ | close 中 DB 关闭与 CSAT 移出 catch，catch 仅包裹 Redis 状态处理 |
| M3 | ✅ | 入队失败返回明确失败提示，不再误发 TRANSFER 事件 |
| M4 | ✅ | 保存 `Disposable`，emitter onCompletion/onTimeout/onError 中 dispose |
| M5 | ✅ | RetryInterceptor 置于最外层，重试重新签名 |
| M6 | ✅ | 领域缓存失效 + 事件发布改到 afterCommit |
| M7 | ✅ | PersistHandler 分布式锁持有到事务完成（afterCompletion）后释放 |
| M8 | ✅ | humanCount 过滤改为 `tag IS DISTINCT FROM 'AI 对话'` |
| M9 | ✅ | 新增 `addChunk`，超长单片继续下钻下一层分隔符 |
| L1 | ✅ | CsatRatingMapper 新增 `insertIfAbsent`（ON CONFLICT DO NOTHING）+ 回查，依赖 uq_csat_session |
| L2 | ✅ | triggerCsat 对 agentId 的 parseLong 加 try/catch，非数字降级为 null |
| L3 | ✅ | sendCode 已锁定时拒绝发送，防止重发清零错误计数绕过锁定 |
| L4 | ✅ | TAG_UPDATED 事件改到 afterCommit 发布 |
| L5 | ✅ | WS 消息长度按 UTF-8 实际字节数校验 |
| L6 | ✅ | closedLimit 收敛到 [1, 200] |

---

## ⚠️ 评审中新发现（未在原清单，需决策）

### N1. `assignRoles` 角色变更从不落库（功能空操作）✅ 已修复
- **文件**：`ai-auth/auth-service/.../infrastructure/repository/UserRepositoryImpl.java`（`save` / `toDO`）、`application/.../UserApplicationService.assignRoles`
- **问题**：`assignRoles` 最终调用 `userRepo.save(user)`，但 `save` 只持久化 `UserDO`，从不写 `sys_user_role` 关联表；`toDO` 也不处理 `roleIds`。`RoleMapper` 仅有角色-权限（role_permission）的读写方法，没有 user-role 的写方法。因此为用户分配/变更角色实际上不生效。
- **影响**：管理端「分配角色」接口调用成功但角色未变更，权限体系失真（此接口现受 `@SaCheckRole("admin")` 保护，非越权，但功能失效）。
- **建议修复（需确认）**：在 `RoleMapper`（或新建 UserRoleMapper）新增 `sys_user_role` 的 `deleteByUserId` + 批量 `insert`，并在 `save` 中于用户主记录持久化后按 `roleIds` 覆盖写关联表（同一事务内）。因涉及新增 SQL/Mapper 与写表语义，待确认后再实施。
- **实际修复**：`RoleMapper` 新增 `deleteUserRoles` + `insertUserRoles`（XML 实现）；`UserRepositoryImpl.save` 于用户主记录持久化后调用 `syncUserRoles`，按聚合根 `roleIds`「先删后插」写 `sys_user_role`（幂等，处于 `assignRoles` 的 `@Transactional` 边界内）。auth-service 编译通过。
- **部署验证（2026-07-30 接口自动化）**：登录 superadmin 调用 `POST /api/v1/users/1003/roles {roleIds:[11,12]}`，直连 PG 查 `sys_user_role` 确认 user 1003 由 `{12}` 变为 `{11,12}`，验证后还原为 `{12}`。N1 落库确认生效。

---

### N2. `UserController` 权限注解用了不存在的角色名 `admin`（H1 引入的回归）✅ 已修复
- **文件**：`ai-auth/auth-service/.../interfaces/rest/UserController.java`
- **问题**：H1 为管理端端点补上 `@SaCheckRole("admin")`，但系统实际角色 key 为 `super_admin`/`kf_manager`/`kf_staff`，**不存在 `admin` 角色**（`StpInterfaceImpl` 从 token 读 `role_key` 列表）。导致包括 superadmin 在内的所有用户均无法访问用户管理/分配角色接口（报 `NotRoleException`）。
- **发现方式**：部署后接口自动化验证：superadmin `GET /api/v1/users` 返回 500（预期 200），assignRoles 也返回 500。
- **实际修复**：将 `UserController` 内 7 处 `@SaCheckRole("admin")` 与 1 处 `StpUtil.checkRole("admin")` 统一改为 `super_admin`（与 `SystemConfigService` / `UserInfoApplicationService` 现有用法一致）。重新部署后：superadmin 放行 200、kfstaff 被拒 403。

---

### N3. `NotRoleException` 未被全局异常处理器捕获（返回 500 而非 403）✅ 已修复
- **文件**：`ai-common/common-web/.../exception/GlobalExceptionHandler.java`
- **问题**：处理器已映射 `NotLoginException→401`、`NotPermissionException→403`，但缺 `NotRoleException` 处理，角色不足时落到通用 500。
- **实际修复**：新增 `@ExceptionHandler(NotRoleException.class)` 返回 403；重新部署后 kfstaff 访问管理接口正确返回 403。

---

## 已排查确认无问题的关键面

- 会话状态机 `SessionStatus.transitionTo` 转换规则正确
- MQ 时间戳单位（发布/消费均秒级）一致
- 座席 SSE 引用计数下线、per-session 发送锁、注册表条件式移除均正确
- RRF 融合公式与排序方向正确；PgVector/Jsonb TypeHandler 读写对称
- 全部 MyBatis XML 使用参数化占位符，无 SQL 注入
- TraceIdFilter / MQ 消费者 MDC 均在 finally 清理，无 ThreadLocal 串号
- 限流 Lua 原子脚本、内部密钥恒定时间比较实现正确
- 雪花 ID 生成器同步且处理时钟回拨；MinIO 上传下载用 try-with-resources
