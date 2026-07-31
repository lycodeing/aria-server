# conversation-service 接口自动化测试用例

服务前缀：`https://localhost/conversation`（本地网关，剥掉后转发到 `aria-conversation`，实际路径以 `/api/v1/...` 开头）
鉴权：Sa-Token，`Authorization: Bearer <token>`；访客域接口（`/api/v1/chat/**`、`/api/v1/business-hours/status`）整体免登录白名单。

---

## 1. 访客会话初始化 `POST /api/v1/chat/session/init`

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-SESS-001 | 缺少 X-Anonymous-Id 头被拒 | 无 | POST `/api/v1/chat/session/init`，body `{}`，不带 `X-Anonymous-Id` 头 | HTTP 非 200（400 系） | P0 | |
| CONV-SESS-002 | X-Anonymous-Id 太短（<8）被拒 | 无 | 带 `X-Anonymous-Id: short` | HTTP 400 | P0 | |
| CONV-SESS-003 | X-Anonymous-Id 含非法字符被拒 | 无 | 带 `X-Anonymous-Id: bad id with space!` | HTTP 400 | P1 | 校验字符集（应仅允许字母数字及少量符号） |
| CONV-SESS-004 | 首次 init 创建新会话 | 生成唯一 `anonymousId`（≥8位合法字符，如 `autotest-anon-<ts>`） | POST body `{"visitorName":"自动化测试访客"}`，带对应 `X-Anonymous-Id` | HTTP 200；`data.isNew=true`；`data.status="AI_CHAT"`；返回 `sessionId` | P0 | |
| CONV-SESS-005 | 同一 anonymousId 重复 init 复用会话 | 承接 CONV-SESS-004 的 anonymousId | 再次 POST init（可换 `visitorName`） | HTTP 200；`data.isNew=false`；`data.sessionId` 与首次相同 | P0 | 验证按 anonymousId 做会话复用，而非按 visitorName |
| CONV-SESS-006 | 会话状态查询（存在） | 承接已创建 sessionId | GET `/api/v1/chat/state?sessionId={sessionId}` | HTTP 200；`data.status="AI_CHAT"` | P0 | |
| CONV-SESS-007 | 会话状态查询（不存在的 sessionId 兜底） | 无 | GET `/api/v1/chat/state?sessionId=no-such-session-<ts>` | HTTP 200；`data.status="AI_CHAT"`（兜底默认值，不报 404） | P1 | 语义：查不存在的会话不报错，返回默认 AI_CHAT 状态 |
| CONV-SESS-008 | 非法 sessionId 格式被拒 | 无 | GET `/api/v1/chat/state?sessionId=bad%20id%21` | 业务码 400 | P1 | |
| CONV-SESS-009 | CLOSED 会话后重新 init 新建会话 | 已存在一个 CLOSED 状态的会话（关联某 anonymousId） | 用同一 anonymousId 再次 POST init | HTTP 200；`data.isNew=true`；新 `sessionId` 与已关闭的不同 | P0 | 验证 CLOSED 状态不可复用，必须新建 |

---

## 2. 对话（流式 / 非流式 / 历史 / 反馈）

### 2.1 `POST /api/v1/chat/stream`（SSE）

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-CHAT-001 | 空消息 stream 直接返回 done | 已有 sessionId（AI_CHAT 状态） | POST stream body `{"sessionId":"...","message":""}` | SSE 流中出现 `[DONE]`，不调用 AI | P0 | 不依赖 AI，可离线验证 |
| CONV-CHAT-002 | 非法 sessionId stream 返回 error 事件 | 无 | POST stream body `{"sessionId":"bad id!","message":"你好"}` | SSE 流中出现错误提示（含"非法"字样或 error 事件） | P1 | |
| CONV-CHAT-003 | AI 流式对话返回真实回复 | 已有 sessionId（AI_CHAT），SKIP_AI=false | POST stream body `{"sessionId":"...","message":"你好，请介绍一下你自己"}` | SSE 流中出现 `data:` 前缀的分片数据 | P0 | 依赖 LLM，标记 `@pytest.mark.ai` |
| CONV-CHAT-004 | AI 回复异步落库历史 | 承接 CONV-CHAT-003 | 等待 2s 后 GET `/api/v1/chat/history?sessionId=...` | 历史中出现至少 1 条 `role="assistant"` 的消息 | P0 | 依赖 AI |
| CONV-CHAT-005 | ACTIVE 会话（已转人工接入）访客发消息返回固定提示 | 会话已被座席 accept（进入 ACTIVE） | POST stream body `{"sessionId":"...","message":"人工接待中我再发一条"}` | SSE 返回固定提示（含"人工客服"字样），不走 AI | P0 | 不依赖 AI，可离线验证；验证消息仍写入历史 |

### 2.2 `POST /api/v1/chat`（非流式）

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-CHAT-006 | 非流式空消息被拒 | 已有 sessionId | POST `/api/v1/chat` body `{"sessionId":"...","message":""}` | 业务码 400 | P1 | |

### 2.3 `GET/DELETE /api/v1/chat/history`

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-HIST-001 | 历史全量查询 | 会话中已有消息 | GET `/api/v1/chat/history?sessionId=...` | HTTP 200，返回消息数组，含 `seq` 字段 | P0 | |
| CONV-HIST-002 | 历史增量查询（sinceSeq=当前最大值） | 承接 CONV-HIST-001，取 `max(seq)` | GET `/api/v1/chat/history?sessionId=...&sinceSeq={maxSeq}` | HTTP 200，返回空数组 | P0 | seq 在 JSON 中序列化为字符串，需转数字后取最大值 |
| CONV-HIST-003 | 非法 sessionId 历史查询被拒 | 无 | GET `/api/v1/chat/history?sessionId=bad%20id%21` | 业务码 400 | P1 | |
| CONV-HIST-004 | 清除会话历史 | 会话中已有消息 | DELETE `/api/v1/chat/history?sessionId=...` | HTTP 200 | P0 | |
| CONV-HIST-005 | 清除后历史为空 | 承接 CONV-HIST-004 | GET `/api/v1/chat/history?sessionId=...` | HTTP 200，返回空数组 | P0 | |

### 2.4 `POST /api/v1/chat/messages/feedback`

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-FB-001 | 无 AI 回复时反馈回落失败 | 会话中无 assistant 消息 | POST feedback body `{"sessionId":"...","feedback":"up"}` | 业务码 40400 | P1 | |
| CONV-FB-002 | seq=0 参数校验被拒 | 无 | POST feedback body `{"sessionId":"...","seq":0,"feedback":"up"}` | HTTP 400 | P2 | |
| CONV-FB-003 | feedback 非法枚举被拒 | 无 | POST feedback body `{"sessionId":"...","feedback":"sideways"}` | HTTP 400 | P2 | |
| CONV-FB-004 | 反馈点赞 | 会话中已有 assistant 消息（依赖 AI 对话产生） | POST feedback body `{"sessionId":"...","feedback":"up"}` | HTTP 200；`data.feedback="up"` | P0 | 依赖 AI 产生的历史消息 |
| CONV-FB-005 | 点踩覆盖点赞（last-write-wins） | 承接 CONV-FB-004 | POST feedback body `{"sessionId":"...","feedback":"down"}` | `data.feedback="down"` | P0 | |
| CONV-FB-006 | 取消反馈 | 承接 CONV-FB-005 | POST feedback body `{"sessionId":"...","feedback":null}` | HTTP 200 | P1 | |
| CONV-FB-007 | 重复取消反馈幂等 | 承接 CONV-FB-006 | 再次 POST feedback body `{"sessionId":"...","feedback":null}` | HTTP 200（不报错） | P1 | |

---

## 3. 访客短信认证 `POST /api/v1/chat/auth/sms/*`

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-SMS-001 | 手机号格式非法被拒（发送） | 无 | POST `/sms/send` body `{"phone":"12345678901"}` | HTTP 400 | P1 | |
| CONV-SMS-002 | 5位验证码格式被拒（验证） | 无 | POST `/sms/verify` body `{"phone":"<合法手机号>","code":"12345"}` | HTTP 400 | P2 | |
| CONV-SMS-003 | 未发送就验证 → 验证码已过期 | 无 | POST `/sms/verify` body `{"phone":"<新手机号>","code":"123456"}` | HTTP 400 | P1 | |
| CONV-SMS-004 | 发送验证码成功 | 无 | POST `/sms/send` body `{"phone":"<新手机号>"}` | HTTP 200 | P0 | |
| CONV-SMS-005 | 60s 内重复发送被限流 | 承接 CONV-SMS-004 | 立即再次 POST `/sms/send`（同手机号） | HTTP 429 | P0 | |
| CONV-SMS-006 | 错误验证码被拒（含剩余次数提示） | 已发送验证码，从 Redis key `visitor:sms:{phone}` 读取真实验证码 | POST `/sms/verify` body 用错误 code | HTTP 400 | P0 | 需要能访问 Redis 读取验证码（测试基建依赖） |
| CONV-SMS-007 | 正确验证码换取 token（绑定会话） | 承接已发送的验证码 | POST `/sms/verify` body `{"phone":"...","code":"<真实验证码>","sessionId":"<会话id>"}` | HTTP 200；返回 `data.token` | P0 | |
| CONV-SMS-008 | 认证状态回查 authenticated=true | 承接 CONV-SMS-007 | GET `/sms/../auth/state?sessionId=...`（即 `GET /api/v1/chat/auth/state`） | `data.authenticated=true`；`data.phoneMask` 含 `****` 脱敏 | P0 | |
| CONV-SMS-009 | 验证码一次性（重放被拒） | 承接 CONV-SMS-007（验证码已使用） | 用同一验证码再次 POST `/sms/verify` | HTTP 400 | P0 | 验证验证码用后即焚 |
| CONV-SMS-010 | 未认证会话回查 authenticated=false | 无 | GET `/auth/state?sessionId=unauth-sess-<ts>` | `data.authenticated=false` | P1 | |
| CONV-SMS-011 | 连续 5 次错误验证码触发锁定 | 已发送验证码（新手机号） | 连续 5 次 POST `/sms/verify` 用错误 code | 第 5 次返回 HTTP 400（提示已锁定） | P0 | |
| CONV-SMS-012 | 锁定后验证被拒 (423) | 承接 CONV-SMS-011 | 第 6 次 POST `/sms/verify` | HTTP 423 | P0 | |
| CONV-SMS-013 | 锁定后重发验证码同样被拒 (423) | 承接 CONV-SMS-011 | POST `/sms/send`（同手机号） | HTTP 423 | P1 | |

## 4. 转人工与队列状态机

### 4.1 转人工 `POST /api/v1/chat/transfer`

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-XFER-001 | 缺 sessionId 参数校验 | 无 | POST transfer body `{"sessionId":"","userName":"x"}` | HTTP 400 | P1 | |
| CONV-XFER-002 | 访客转人工成功 → WAITING | 已有 AI_CHAT 状态会话 | POST transfer body `{"sessionId":"...","userName":"...","transferReason":"自动化测试","tag":"测试"}` | HTTP 200；`data.status="WAITING"` | P0 | |
| CONV-XFER-003 | 重复转人工幂等（不重置状态） | 承接 CONV-XFER-002 | 再次 POST transfer（不同 reason/tag） | HTTP 200；`data.status` 仍 `WAITING`；`data.transferReason` 保留原值（不被覆盖） | P0 | |
| CONV-XFER-004 | 缺省 transferReason/tag 使用默认值 | 新会话，仅传 sessionId+userName | POST transfer（不传 transferReason/tag） | `data.transferReason="用户主动请求转人工"`；`data.tag="咨询"` | P1 | |
| CONV-XFER-005 | 非服务时间转人工被拦截 | 业务时间配置为全周停业 | POST transfer | 业务码 40301，附带恢复时间提示 | P1 | 依赖业务时间配置，见第 10 节 |

### 4.2 座席队列 `GET /api/v1/sessions`

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-QUEUE-001 | 座席会话队列列表 | 已登录座席 token；已有转人工会话 | GET `/api/v1/sessions` | HTTP 200；转人工会话出现在结果中 | P0 | |
| CONV-QUEUE-002 | closedLimit=1 边界 | 同上 | GET `/api/v1/sessions?closedLimit=1` | HTTP 200 | P2 | |
| CONV-QUEUE-003 | closedLimit 超大值被收敛 | 同上 | GET `/api/v1/sessions?closedLimit=100000` | HTTP 200（不报错，内部收敛） | P2 | |
| CONV-QUEUE-004 | closedLimit 非法值（0/负数）被收敛 | 同上 | GET `/api/v1/sessions?closedLimit=0` 和 `closedLimit=-5` | HTTP 200 | P2 | 已在历史修复验证脚本中覆盖（H/M/L 系列） |
| CONV-QUEUE-005 | 无 token 查询队列被拒 | 无 | GET `/api/v1/sessions`（不带 token） | HTTP 401 | P0 | |

### 4.3 接入 `POST /api/v1/sessions/{id}/accept`

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-ACC-001 | 接入不存在的会话 | 无 | POST `/api/v1/sessions/not-exist-<ts>/accept` | HTTP 404 | P1 | |
| CONV-ACC-002 | 座席接入 WAITING 会话 | 会话处于 WAITING | POST `/api/v1/sessions/{id}/accept`（座席A token） | HTTP 200；`data.status="ACTIVE"`；`data.agentId=座席A的ID` | P0 | |
| CONV-ACC-003 | 重复接入被拒（不换绑座席） | 承接 CONV-ACC-002 | 另一座席B POST 同一 `/accept` | HTTP 409 | P0 | |
| CONV-ACC-004 | 访客侧状态同步为 ACTIVE | 承接 CONV-ACC-002 | GET `/api/v1/chat/state?sessionId={id}` | `data.status="ACTIVE"` | P0 | |

### 4.4 转交 `POST /api/v1/sessions/{id}/transfer`

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-TRF-001 | 非归属座席发起转交被拒（CAS） | 会话归属座席A（ACTIVE） | 座席B POST `/api/v1/sessions/{id}/transfer` body `{"targetAgentId":"..."}` | HTTP 409 | P0 | 验证会话归属的 CAS 语义 |
| CONV-TRF-002 | 转交给不在线座席被拒 | 会话归属当前座席 | POST transfer body `{"targetAgentId":"offline-agent-<ts>"}` | HTTP 400 | P0 | |
| CONV-TRF-003 | targetAgentId 非法格式被拒 | 同上 | POST transfer body `{"targetAgentId":"bad id!"}` | HTTP 400 | P1 | |
| CONV-TRF-004 | targetAgentId 为空被拒 | 同上 | POST transfer body `{"targetAgentId":""}` | HTTP 400 | P1 | |
| CONV-TRF-005 | 转交成功 | 会话归属当前座席，目标座席在线（已建立 SSE 连接） | POST transfer body `{"targetAgentId":"<在线座席ID>"}` | HTTP 200 | P0 | |
| CONV-TRF-006 | 转交后归属最终一致（MQ 异步同步） | 承接 CONV-TRF-005 | 轮询 GET `/api/v1/sessions`（最多约 6s），比对该会话 `agentId` | 最终变为目标座席 ID | P0 | DB 经 MQ 异步同步，需轮询等待，不可断言立即一致 |
| CONV-TRF-007 | WAITING 会话不可转交 | 新建会话，转人工后处于 WAITING（未 accept） | POST `/api/v1/sessions/{id}/transfer` body `{"targetAgentId":"..."}` | HTTP 409 | P0 | 只有 ACTIVE 会话可转交 |

### 4.5 在线座席 `GET /api/v1/sessions/agents/online`

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-ON-001 | SSE 建连后座席出现在在线列表 | 座席已建立 `/api/v1/sessions/events` SSE 长连接 | GET `/api/v1/sessions/agents/online` | HTTP 200；结果中含该座席（字段为 `id`，非 `agentId`） | P0 | 在线状态依赖 SSE 长连接注册，非登录即在线 |

### 4.6 会话关闭 `POST /api/v1/sessions/{id}/close`

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-CLOSE-001 | 座席关闭会话 | 会话 ACTIVE，归属当前座席 | POST `/api/v1/sessions/{id}/close` | HTTP 200 | P0 | |
| CONV-CLOSE-002 | 关闭后状态 CLOSED | 承接 CONV-CLOSE-001 | GET `/api/v1/chat/state?sessionId={id}` | `data.status="CLOSED"` | P0 | |
| CONV-CLOSE-003 | 重复关闭幂等 | 承接 CONV-CLOSE-001 | 再次 POST `/close` | HTTP 200（不报错） | P1 | |

### 4.7 访客历史 `GET /api/v1/sessions/visitor-history`

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-VH-001 | 按匿名 ID 查询 | 座席 token + `X-Anonymous-Id` 头 | GET `/api/v1/sessions/visitor-history`（带 `X-Anonymous-Id`） | HTTP 200 | P1 | |
| CONV-VH-002 | 按访客名查询 | 座席 token | GET `/api/v1/sessions/visitor-history?visitorName=...` | HTTP 200 | P1 | |
| CONV-VH-003 | 缺身份标识 | 座席 token，不带 anonymousId/visitorName | GET `/api/v1/sessions/visitor-history` | 业务码 400 | P1 | |
| CONV-VH-004 | 已关闭会话仍可在访客历史中查到 | 某 anonymousId 有一个 CLOSED 会话和一个新会话 | GET `/api/v1/sessions/visitor-history?excludeSessionId={新会话id}`（带该 anonymousId） | 结果中含已关闭会话 | P1 | |

### 4.8 AI 辅助能力

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-AI-001 | AI 回复建议 | 会话 ACTIVE，座席 token | POST `/api/v1/sessions/{id}/reply-suggestions` body `{"lastMessage":"请问退货政策是什么"}` | HTTP 200 | P1 | 依赖 LLM，标记 `@pytest.mark.ai` |
| CONV-AI-002 | AI 摘要查询 | 会话已有对话内容 | GET `/api/v1/sessions/{id}/ai-summary` | HTTP 200 | P1 | 缓存查询，不一定实时触发生成 |
| CONV-AI-003 | AI 摘要流式版 | 同上 | GET `/api/v1/sessions/{id}/ai-summary/stream`（SSE，`@SaIgnore` 需手动带 token） | SSE 流正常返回 | P2 | |

---

## 5. 座席 SSE 事件流 `GET /api/v1/sessions/events`

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-SSE-001 | SSE 握手返回 connected 注释 | 有效座席 token | GET `/api/v1/sessions/events?token={token}`（流式） | 流中出现 `:connected` 注释行 | P0 | token 走 query 参数（`@SaIgnore` 手动校验），非 header |
| CONV-SSE-002 | 转人工事件实时推送 | 已建立 SSE 连接 | 触发一次转人工（其他请求） | 流中出现该会话的 ENQUEUE 事件（含 sessionId） | P0 | |
| CONV-SSE-003 | 无效 token SSE 被拒 | 无 | GET `/api/v1/sessions/events?token=invalid-token` | HTTP 401 | P0 | |

## 6. CSAT 满意度评价全状态机

### 6.1 待评价查询 `GET /api/v1/chat/csat/pending`

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-CSAT-001 | 会话关闭后生成 CSAT 邀请 | 会话刚被座席关闭 | 等待约 2s 后 GET `/api/v1/chat/csat/pending?sessionId={id}` | HTTP 200；`data.csatId` 非空 | P0 | 邀请生成为异步流程，需短暂等待 |
| CONV-CSAT-002 | 非法 sessionId 参数校验 | 无 | GET `/api/v1/chat/csat/pending?sessionId=bad%20id%21` | 业务码 400 | P1 | |

### 6.2 评分 `POST /api/v1/chat/csat/{csatId}/rate`

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-CSAT-003 | score 越界被拒 | 已有 csatId | POST `/rate` body `{"score":6,"comment":"越界"}` | HTTP 400 | P1 | score 合法范围 1-5 |
| CONV-CSAT-004 | 提交评分成功 | 同上 | POST `/rate` body `{"score":5,"comment":"好评"}` | HTTP 200 | P0 | |
| CONV-CSAT-005 | 重复评分被拒 | 承接 CONV-CSAT-004 | 再次 POST `/rate` body `{"score":4,...}` | 业务码 40901 | P0 | 已评分不可覆盖 |
| CONV-CSAT-006 | 评分不存在的邀请 | 无 | POST `/api/v1/chat/csat/999999999/rate` body `{"score":5}` | 业务码 40400 | P1 | |
| CONV-CSAT-007 | 评分后 pending 查询返回空 | 承接 CONV-CSAT-004 | GET `/pending?sessionId={id}` | `data` 为空/null | P0 | |

### 6.3 跳过 `POST /api/v1/chat/csat/{csatId}/skip`

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-CSAT-008 | 已评分后 skip 静默幂等 | 承接 CONV-CSAT-004 | POST `/skip` | HTTP 200（不报错，也不改变已评分结果） | P1 | |
| CONV-CSAT-009 | 跳过评价 | 新的待评价 csatId（未评分） | POST `/skip` | HTTP 200 | P0 | |
| CONV-CSAT-010 | 重复 skip 幂等 | 承接 CONV-CSAT-009 | 再次 POST `/skip` | HTTP 200 | P1 | |
| CONV-CSAT-011 | skip 后再评分被拒 | 承接 CONV-CSAT-009 | POST `/rate` body `{"score":5}` | 业务码 40901 | P0 | skip 是终态，等价于已处理 |
| CONV-CSAT-012 | skip 后 pending 查询返回空 | 承接 CONV-CSAT-009 | GET `/pending?sessionId={id}` | `data` 为空/null | P1 | |

---

## 7. 会话备注 `POST/GET/PUT/DELETE /api/v1/sessions/{id}/notes`

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-NOTE-001 | 新增会话备注 | 座席 token，有效会话 | POST `/notes` body `{"content":"测试备注"}` | HTTP 200 | P0 | |
| CONV-NOTE-002 | 备注列表 | 承接 CONV-NOTE-001 | GET `/notes` | HTTP 200；结果含新增备注 | P0 | |
| CONV-NOTE-003 | 修改备注 | 承接 CONV-NOTE-001 | PUT `/notes/{noteId}` body `{"content":"改后内容"}` | HTTP 200；再次 GET 列表内容已更新 | P1 | |
| CONV-NOTE-004 | 删除备注 | 承接 CONV-NOTE-003 | DELETE `/notes/{noteId}` | HTTP 200；再次 GET 列表不再出现该内容 | P1 | |
| CONV-NOTE-005 | 无 token 备注列表被拒 | 无 | GET `/api/v1/sessions/{id}/notes`（不带 token） | HTTP 401 | P0 | |

---

## 8. 标签

### 8.1 标签字典 `POST/GET/PUT/DELETE /api/v1/admin/tags`

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-TAG-001 | 创建标签字典 | admin token | POST `/api/v1/admin/tags` body `{"name":"标签X","color":"#FF5722"}` | HTTP 200 | P0 | |
| CONV-TAG-002 | 重复标签名被拒 | 承接 CONV-TAG-001（同名） | POST 同名标签 | 非 200 业务码 | P1 | |
| CONV-TAG-003 | 标签字典列表 | 同上 | GET `/api/v1/admin/tags` | HTTP 200 | P0 | |
| CONV-TAG-004 | 修改标签字典 | 承接 CONV-TAG-001 | PUT `/{tagId}` body `{"name":"标签X-改","color":"#2196F3","source":"CUSTOM"}` | HTTP 200 | P1 | |
| CONV-TAG-005 | kfstaff 标签字典访问策略 | kfstaff token | GET `/api/v1/admin/tags` | 记录实际返回码（403 或 200，取决于当前权限配置） | P2 | 需在实测中确认真实行为并记录，不预设 |

### 8.2 会话/访客标签关联

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-TAG-006 | 会话添加标签 | 有效标签ID + 会话 | POST `/api/v1/sessions/{id}/tags` body `{"tagId":N}` | HTTP 200 | P0 | |
| CONV-TAG-007 | 重复打标行为 | 承接 CONV-TAG-006 | 再次 POST 相同 tagId | 记录实际返回码（幂等200或业务拒绝均视为通过） | P2 | |
| CONV-TAG-008 | 会话标签列表含新标签 | 承接 CONV-TAG-006 | GET `/api/v1/sessions/{id}/tags` | 结果含该 tagId | P0 | |
| CONV-TAG-009 | 打不存在的标签被拒 | 无 | POST `/tags` body `{"tagId":99999999}` | 非 200 业务码 | P1 | |
| CONV-TAG-010 | 会话移除标签 | 承接 CONV-TAG-006 | DELETE `/api/v1/sessions/{id}/tags/{tagId}` | HTTP 200 | P1 | |
| CONV-TAG-011 | 访客添加/查询/移除标签 | 同上 | POST/GET/DELETE `/api/v1/sessions/{id}/visitor/tags[/{tagId}]` | 均 HTTP 200 | P1 | 与会话标签接口对称，独立的访客维度标签 |

---

## 9. 快捷回复

### 9.1 管理端公共模板与分组

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-CR-001 | 创建快捷回复分组 | admin token | POST `/api/v1/admin/canned-response-groups` body `{"name":"分组X","sortOrder":99}` | HTTP 200 | P0 | |
| CONV-CR-002 | 分组列表 | 同上 | GET `/api/v1/admin/canned-response-groups` | HTTP 200 | P0 | |
| CONV-CR-003 | 更新分组 | 承接 CONV-CR-001 | PUT `/{groupId}` body `{"name":"分组X-改","sortOrder":98}` | HTTP 200 | P1 | |
| CONV-CR-004 | 创建公共快捷回复 | 承接 CONV-CR-001（有 groupId） | POST `/api/v1/admin/canned-responses` body `{"title":"...","content":"...","groupId":N,"sortOrder":1}` | HTTP 200 | P0 | |
| CONV-CR-005 | 公共快捷回复列表 | 承接 CONV-CR-004 | GET `/api/v1/admin/canned-responses?page=1&size=10` | HTTP 200 | P0 | |
| CONV-CR-006 | 更新公共快捷回复 | 承接 CONV-CR-004 | PUT `/{crId}` body `{...}` | HTTP 200 | P1 | |
| CONV-CR-007 | 删除公共快捷回复/分组（清理） | 承接以上 | DELETE `/canned-responses/{id}`，DELETE `/canned-response-groups/{groupId}` | HTTP 200 | P1 | |

### 9.2 坐席端搜索与使用

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-CR-008 | 坐席搜索快捷回复（整词命中） | 已创建标题为完整 token 的公共回复 | GET `/api/v1/canned-responses/search?q={完整标题}` | HTTP 200；结果含该回复 | P0 | PG `to_tsvector('simple')` 对中文不分词，只能整词命中，搜子串不会命中 |
| CONV-CR-009 | 上报使用次数 | 承接 CONV-CR-004 | POST `/api/v1/canned-responses/{id}/use` | HTTP 200 | P1 | |

### 9.3 私人模板数据隔离

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-CR-010 | kfstaff 创建私人模板 | kfstaff token | POST `/api/v1/canned-responses/mine` body `{"title":"...","content":"..."}` | HTTP 200 | P0 | |
| CONV-CR-011 | kfstaff 私人模板列表 | 承接 CONV-CR-010 | GET `/api/v1/canned-responses/mine`（kfstaff token） | 结果含刚创建的模板 | P0 | |
| CONV-CR-012 | 私人模板跨账号不可见 | 承接 CONV-CR-010 | GET `/mine`（superadmin token） | 结果不含 kfstaff 的私人模板 | P0 | 数据隔离核心断言 |
| CONV-CR-013 | 跨账号修改私人模板被拒 | 承接 CONV-CR-010 | PUT `/mine/{id}`（superadmin token，非本人） | 非 200 业务码 | P0 | |
| CONV-CR-014 | 本人更新/删除私人模板 | 承接 CONV-CR-010 | PUT/DELETE `/mine/{id}`（kfstaff token） | HTTP 200 | P1 | |

## 10. SLA 策略

### 10.1 策略 CRUD `POST/GET/PUT/DELETE /api/v1/admin/sla/policies`

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-SLA-001 | SLA 策略列表 | admin token | GET `/api/v1/admin/sla/policies` | HTTP 200 | P0 | |
| CONV-SLA-002 | kfstaff 访问 SLA 策略被拒 | kfstaff token | GET `/api/v1/admin/sla/policies` | HTTP 403 | P0 | 权限码 `system:sla:manage`/`view` |
| CONV-SLA-003 | timeMode 非法枚举被拒 | admin token | POST 创建策略 body `timeMode:"INVALID"` | HTTP 400 | P1 | 合法值 CALENDAR/BUSINESS_HOURS |
| CONV-SLA-004 | warningThresholdPct 越界被拒 | 同上 | POST body `warningThresholdPct:0` | HTTP 400 | P1 | 合法范围应大于0 |
| CONV-SLA-005 | 缺少 actions 被拒 | 同上 | POST body 不含 `actions` 字段 | HTTP 400 | P1 | |
| CONV-SLA-006 | 创建 SLA 策略成功 | 同上 | POST body 含完整合法字段（`timeMode:CALENDAR`, `waitTimeTargetSec`, `frtTargetSec`, `handleTimeTargetSec`, `warningThresholdPct`, `actions`） | HTTP 200；返回 `data.id` | P0 | |
| CONV-SLA-007 | 更新 SLA 策略 | 承接 CONV-SLA-006 | PUT `/{id}` body 修改字段 | HTTP 200；再次 GET 列表字段已更新 | P1 | |
| CONV-SLA-008 | 删除 SLA 策略（清理） | 承接 CONV-SLA-006 | DELETE `/{id}` | HTTP 200 | P1 | |

### 10.2 违规记录查询 `GET /api/v1/admin/sla/breaches`

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-SLA-009 | 违规记录列表 | admin token | GET `/api/v1/admin/sla/breaches` | HTTP 200 | P0 | |
| CONV-SLA-010 | 按类型过滤 | 同上 | GET `?breachType=WAIT&page=1&pageSize=5` | HTTP 200 | P1 | |
| CONV-SLA-011 | 按日期过滤 | 同上 | GET `?startDate=2026-01-01&endDate=2026-12-31` | HTTP 200 | P2 | |
| CONV-SLA-012 | pageSize 超大值被收敛 | 同上 | GET `?pageSize=100000` | HTTP 200（不报错，参数被内部收敛） | P2 | |

### 10.3 违规实测（耗时用例）

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-SLA-013 | WAIT 超时违规被扫描器捕获 | 创建启用态策略 `waitTimeTargetSec=5`；新会话转人工进入 WAITING | 轮询等待最多 75s，GET `/breaches?sessionId={id}` | 结果出现 `breachType=WAIT` 的记录 | P2 | 标记 `slow`；扫描器周期约 30s；测试完成后需接入并关闭会话、删除实测策略 |

---

## 11. Webhook

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-WH-001 | Webhook 列表 | admin token | GET `/api/v1/admin/sla/webhooks` | HTTP 200 | P0 | |
| CONV-WH-002 | 非 HTTPS URL 被拒 | 同上 | POST body `url:"http://insecure.example.com/hook"` | HTTP 400 | P1 | 强制 HTTPS |
| CONV-WH-003 | 缺少 name 被拒 | 同上 | POST body 不含 `name` | HTTP 400 | P1 | |
| CONV-WH-004 | 创建 CUSTOM Webhook | 同上 | POST body 含 `name/type/url/customHeaders/isEnabled` | HTTP 200；返回 `data.id` | P0 | |
| CONV-WH-005 | 更新 Webhook | 承接 CONV-WH-004 | PUT `/{id}` body 修改字段（含 `messageTemplate`） | HTTP 200 | P1 | |
| CONV-WH-006 | 测试发送已执行 | 承接 CONV-WH-004 | POST `/{id}/test` | 业务码 200 或 500（两者都证明链路被执行；网关自签证书场景下常见 500） | P1 | 断言"链路执行"而非固定业务码 |
| CONV-WH-007 | 测试不存在的 Webhook | 无 | POST `/api/v1/admin/sla/webhooks/999999999/test` | 业务码 40400 | P1 | |
| CONV-WH-008 | 删除 Webhook（清理） | 承接 CONV-WH-004 | DELETE `/{id}` | HTTP 200 | P1 | |

---

## 12. 业务时间（排班/节假日/离线回复）

### 12.1 状态查询与排班

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-BH-001 | 业务时间状态（匿名可访问） | 无 | GET `/api/v1/business-hours/status`（不带 token） | HTTP 200 | P0 | 该接口标注 `@SaIgnore`，访客可访问 |
| CONV-BH-002 | 排班查询 | admin token | GET `/api/v1/admin/business-hours/schedule` | HTTP 200；含 7 天配置 | P0 | |
| CONV-BH-003 | kfstaff 排班管理被拒 | kfstaff token | GET 同上 | HTTP 403 | P0 | 权限码 `system:biz-hours:manage` |

### 12.2 全周停业拦截演练（有状态变更风险，需严格还原）

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-BH-004 | 备份现有排班 | admin token | GET `/schedule`，本地保存响应 | 响应含 7 天数据 | P0 | 测试基础设施步骤，非独立断言点，失败应中止后续演练用例避免破坏生产排班 |
| CONV-BH-005 | 设置全周停业 | 承接 CONV-BH-004 | PUT `/schedule` body 全部 `isOpen:false` | HTTP 200 | P1 | |
| CONV-BH-006 | 停业后状态反映 | 承接 CONV-BH-005 | GET `/api/v1/business-hours/status` | `data.open=false`；`data.nextOpenTime` 非空 | P1 | |
| CONV-BH-007 | 非服务时间转人工被拦截 | 承接 CONV-BH-005；新建访客会话 | POST `/api/v1/chat/transfer` body `{sessionId,userName}` | 业务码 40301 | P0 | 应携带恢复时间提示 |
| CONV-BH-008 | 恢复排班 | 承接 CONV-BH-004 备份 | PUT `/schedule` 恢复备份内容 | HTTP 200 | P0 | **测试 teardown 必须执行此步骤，即使前序用例失败也要恢复，避免影响其他测试和真实业务** |
| CONV-BH-009 | 恢复后转人工放行（若当前处于营业时段） | 承接 CONV-BH-008，且当前真实时间在营业时段内 | POST `/transfer` | HTTP 200 | P2 | 依赖执行时刻的真实时间，非营业时段时应跳过而非判失败 |

### 12.3 节假日 CRUD

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-BH-010 | 新增节假日 | admin token；先清理同日期残留记录（date 唯一约束） | POST `/holidays` body `{"date":"2099-12-31","type":"CLOSED","remark":"..."}` | HTTP 200 | P0 | 用远期日期避免影响真实业务 |
| CONV-BH-011 | 节假日已入列表 | 承接 CONV-BH-010 | GET `/holidays?year=2099` | 结果含该记录 | P0 | |
| CONV-BH-012 | 修改节假日为 CUSTOM 时段 | 承接 CONV-BH-010 | PUT `/holidays/{id}` body `{"type":"CUSTOM","timeRanges":[...]}` | HTTP 200 | P1 | |
| CONV-BH-013 | 删除节假日（清理） | 承接 CONV-BH-010 | DELETE `/holidays/{id}` | HTTP 200 | P1 | |
| CONV-BH-014 | type 非法枚举被拒 | 无 | POST body `{"date":"2099-12-31","type":"BAD_TYPE"}` | HTTP 400 | P1 | |

### 12.4 离线自动回复（已知未完全实现）

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-BH-015 | 离线回复查询 | admin token | GET `/offline-reply` | HTTP 200 | P1 | |
| CONV-BH-016 | 更新离线回复（已知 stub） | 同上 | PUT `/offline-reply` body `{"message":"测试文案"}`，随后 GET 校验 | 【缺陷验证】PUT 接口返回 HTTP 200，但写入可能不生效（代码 TODO：待 AuthClient 支持写操作）。测试应记录实际是否生效，而非直接判定失败 | P2 | 已知未实现功能，非本次测试要修复的缺陷，仅需验证并记录现状 |
| CONV-BH-017 | 还原离线回复 | 承接 CONV-BH-016 | PUT `/offline-reply` 恢复原文案 | HTTP 200 | P2 | teardown 步骤 |

---

## 13. Dashboard 看板统计

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| CONV-DASH-001 | overview | admin token | GET `/api/v1/dashboard/overview` | HTTP 200 | P0 | |
| CONV-DASH-002 | status-distribution | 同上 | GET `/api/v1/dashboard/status-distribution` | HTTP 200 | P1 | |
| CONV-DASH-003 | tag-distribution | 同上 | GET `/api/v1/dashboard/tag-distribution` | HTTP 200 | P1 | |
| CONV-DASH-004 | agent-workload | 同上 | GET `/api/v1/dashboard/agent-workload` | HTTP 200 | P1 | |
| CONV-DASH-005 | complexity-distribution | 同上 | GET `/api/v1/dashboard/complexity-distribution` | HTTP 200 | P2 | |
| CONV-DASH-006 | csat-distribution | 同上 | GET `/api/v1/dashboard/csat-distribution` | HTTP 200 | P1 | |
| CONV-DASH-007 | conversation-trends（参数化） | 同上 | GET `/api/v1/dashboard/conversation-trends?days=7` | HTTP 200 | P1 | |
| CONV-DASH-008 | message-trends（参数化） | 同上 | GET `?days=7` | HTTP 200 | P2 | |
| CONV-DASH-009 | efficiency-trends（参数化） | 同上 | GET `?days=7` | HTTP 200 | P2 | |
| CONV-DASH-010 | csat-trend（参数化） | 同上 | GET `?days=7` | HTTP 200 | P2 | |
| CONV-DASH-011 | recent-sessions（参数化） | 同上 | GET `?limit=5` | HTTP 200 | P1 | |
| CONV-DASH-012 | csat-by-agent | 同上 | GET `?days=30` | HTTP 200 | P2 | |
| CONV-DASH-013 | csat-overview | 同上 | GET `?days=30` | HTTP 200 | P2 | |
| CONV-DASH-014 | 无 token 访问被拒 | 无 | GET `/api/v1/dashboard/overview`（不带 token） | HTTP 401 | P0 | |
| CONV-DASH-015 | recent-sessions 数据一致性 | 已有至少一条已关闭会话 | GET `/recent-sessions?limit=10` | 结果非空，包含最近关闭的会话 | P2 | 依赖测试执行顺序产生的数据，建议放在整套 conversation 测试的末尾执行 |

## 12. DIT 意图路由（领域/意图/槽位/工具/绑定 全链路 CRUD）

范围：`DitDomainController`（`/api/v1/admin/dit/domains`）、`DitIntentController`（`/api/v1/admin/dit/intents|slots|bindings`）、`DitToolController`（`/api/v1/admin/dit/tools`）。均仅要求 `@SaCheckLogin`，**无权限码限制**（与角色/菜单管理一样存在权限缺陷，见附录）。

### 12.1 领域（Domain）CRUD

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| DIT-001 | 领域列表查询 | superadmin 登录 | GET `/api/v1/admin/dit/domains` | 200 | P0 | |
| DIT-002 | 创建领域缺 code 被拒 | - | POST `/domains` body `{"name":"缺code"}` | 400 | P1 | code 为必填 |
| DIT-003 | 创建领域成功（含关键词/正则路由） | - | POST `/domains` body `{"code":"autotest_dom_$TS","name":"自动化领域","description":"测试","enabled":false,"keywords":"[\"自动化专用词\"]","patterns":"[\"^自动化正则.*\"]"}` | 200，返回 domainId | P0 | keywords/patterns 是 JSON 字符串形式存储 |
| DIT-004 | 非法正则 patterns 被拒 | - | POST `/domains` body 含 `"patterns":"[\"[未闭合\"]"` | 400 | P1 | 正则编译校验 |
| DIT-005 | 更新领域 | 已有 DOM_ID | PUT `/domains/{id}` 改 name/enabled | 200 | P1 | |
| DIT-006 | 删除领域（清理） | - | DELETE `/domains/{id}` | 200 | P2 | |
| DIT-007 | kfstaff 访问领域列表 | kfstaff 登录 | GET `/domains` | **200**（无权限校验，缺陷） | P1 | 见附录缺陷清单 |

### 12.2 意图（Intent）CRUD

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| DIT-010 | 创建意图（挂在临时领域下） | DOM_ID 存在 | POST `/intents` body `{"domainId":DOM_ID,"code":"autotest_intent_$TS","name":"自动化意图","description":"测试意图","autoTransfer":false,"skipRag":true,"keywords":"[\"自动化意图词\"]"}` | 200，返回 intentId | P0 | |
| DIT-011 | 意图列表按领域过滤 | - | GET `/intents?domainId=DOM_ID` | 200，仅含该领域下意图 | P0 | |
| DIT-012 | 创建意图缺 domainId 被拒 | - | POST `/intents` body `{"code":"x","name":"x","description":"x"}` | 400 | P1 | domainId 必填 |
| DIT-013 | 更新意图 | INT_ID 存在 | PUT `/intents/{id}` 改 name/description | 200 | P1 | |
| DIT-014 | 删除意图（清理） | - | DELETE `/intents/{id}` | 200 | P2 | |

### 12.3 槽位（Slot）CRUD

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| DIT-020 | 创建槽位 | INT_ID 存在 | POST `/slots` body `{"intentId":INT_ID,"slotName":"orderId","slotType":"STRING","description":"订单号","required":true,"askUserPrompt":"请提供订单号"}` | 200，返回 slotId | P0 | |
| DIT-021 | 槽位列表按意图过滤 | - | GET `/slots?intentId=INT_ID` | 200 | P0 | |
| DIT-022 | 更新槽位 | SLOT_ID 存在 | PUT `/slots/{id}` 改 description/required | 200 | P1 | |
| DIT-023 | 删除槽位（清理） | - | DELETE `/slots/{id}` | 200 | P2 | |

### 12.4 工具（Tool）CRUD

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| DIT-030 | 创建工具（HTTP 类型） | - | POST `/api/v1/admin/dit/tools` body `{"code":"autotest_tool_$TS","name":"自动化工具","description":"HTTP测试工具","toolType":"HTTP","httpMethod":"GET","urlTemplate":"https://nginx/","timeoutMs":3000}` | 200，返回 toolId | P0 | |
| DIT-031 | 工具列表 | - | GET `/tools` | 200 | P0 | |
| DIT-032 | 删除工具（清理） | - | DELETE `/tools/{id}` | 200 | P2 | |
| DIT-033 | 工具测试执行 | TOOL_ID 存在 | POST `/tools/{id}/test` | 200 | P1 | 真实发起一次 HTTP 调用 |

### 12.5 绑定（意图 ⇄ 工具）CRUD

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| DIT-040 | 创建意图-工具绑定 | INT_ID、TOOL_ID 存在 | POST `/bindings` body `{"intentId":INT_ID,"toolId":TOOL_ID,"executionMode":"AUTO","executionOrder":1}` | 200，返回 bindingId | P0 | |
| DIT-041 | 绑定列表按意图过滤 | - | GET `/bindings?intentId=INT_ID` | 200 | P0 | |
| DIT-042 | 删除绑定（清理） | - | DELETE `/bindings/{id}` | 200 | P2 | |

**清理顺序**（避免外键/引用残留）：绑定 → 槽位 → 意图 → 工具 → 领域。

---

## 13. WebSocket 双向通信

范围：座席端 `wss://localhost/ws/agent?token={token}`，访客端 `wss://localhost/ws/chat/{sessionId}`。这部分不是标准 REST 断言，需要专门的 WS 测试工具（现有 `deploy/ws-autotest.py`，pytest 项目中计划用 `pytest-asyncio` + `websockets` 重写，标记 `@pytest.mark.ws`）。

### 13.1 正常双向通信（bridge 场景）

前置：会话 S2 处于 ACTIVE 且归属某座席（先走 REST 转人工 + accept）。

| ID | 标题 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|
| WS-001 | 访客端 WS 建连成功 | 连接 `wss://localhost/ws/chat/{sessionId}` | 握手成功 | P0 | |
| WS-002 | 座席端 WS 建连成功（带 token） | 连接 `wss://localhost/ws/agent?token={agentToken}` | 握手成功 | P0 | |
| WS-003 | 座席→访客消息投递 | 座席端发 `{"type":"MESSAGE","sessionId":S2,"content":"座席WS消息-{ts}"}` | 访客端 15s 内收到含该内容的消息帧 | P0 | |
| WS-004 | 访客→座席消息投递 | 访客端发 `{"type":"MESSAGE","content":"访客WS消息-{ts}"}` | 座席端 15s 内收到含该内容的消息帧 | P0 | |
| WS-005 | 访客 TYPING 事件转发 | 访客端发 `{"type":"TYPING","content":""}` | 座席端 10s 内收到 `type=TYPING` 事件（仅转发不落库） | P1 | |
| WS-006 | 座席消息缺 sessionId 被静默丢弃 | 座席端发 `{"type":"MESSAGE","content":"无sessionId消息-{ts}"}`（不含 sessionId） | 访客端 4s 内**不会**收到该消息 | P1 | 安全边界，防止误投 |
| WS-007 | WS 消息已落历史（座席侧） | 触发 WS-003 后 | GET `/api/v1/chat/history?sessionId=S2` 含"座席WS消息" | P0 | REST/WS 数据一致性 |
| WS-008 | WS 消息已落历史（访客侧） | 触发 WS-004 后 | 历史含"访客WS消息" | P0 | |
| WS-009 | WS 消息后增量同步语义正确 | 取历史最大 seq 后 | GET `/history?sessionId=S2&sinceSeq={maxSeq}` 返回 0 条 | P1 | |

### 13.2 安全负向场景

| ID | 标题 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|
| WS-010 | 座席端无 token 握手被拒 | 连接 `wss://localhost/ws/agent`（不带 token） | 握手返回 401，或连接被立即拒绝/关闭 | P0 | |
| WS-011 | 访客端非法 sessionId 被拒 | 连接 `wss://localhost/ws/chat/bad@session!id` | 服务端先推 error 消息再以关闭码 **1003**（NOT_ACCEPTABLE）主动关闭；或握手直接被拒 | P0 | 需持续收帧直到连接关闭再判定，不能只看第一帧 |

**实现建议**：WS 用例复用 `deploy/ws-autotest.py` 的三个子命令逻辑（`bridge`/`agent-noauth`/`visitor-badsession`），迁移到 pytest 时保留相同的超时策略（15s/10s/4s/8s），避免因超时过短导致偶发误报。

---

## 14. 全流程场景用例（端到端）

前面各节按接口/模块拆分用例，本节把分散的用例串成两条完整业务链路，全程用**同一个 sessionId**（或同一批关联数据）贯穿断言，验证状态机在真实时序下的整体正确性，而不仅是单点接口行为。

### 14.1 场景 A：访客对话全生命周期（AI 对话 → 转人工 → 人工接待 → 关闭 → 评价）

代码依据：`ChatAppService.resolveStream`（`ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/ChatAppService.java:116-124`）——已接入人工（`sessionQueueService.isActive`）→ 固定提示；有 `domainCode` → 域路径；否则 → FAQ 路径。本场景走 FAQ 路径（不传 `domainCode`），场景 B 单独覆盖域路径。

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| E2E-A-001 | 访客初始化会话 | 唯一 `anonymousId`（如 `e2e-full-<ts>`） | POST `/api/v1/chat/session/init` body `{"visitorName":"全流程测试访客"}` | 200；`isNew=true`；`status=AI_CHAT`；记下 `sessionId` | P0 | 全场景复用此 sessionId |
| E2E-A-002 | AI/FAQ 首轮对话 | 承接 E2E-A-001 | POST `/api/v1/chat/stream` body `{"sessionId":"...","message":"你好，请介绍一下你们的退货政策"}` | SSE 流出现 `data:` token 分片；若知识库命中还会先出 `sources` 事件 | P0 | 依赖 AI+RAG，标记 `@pytest.mark.ai` |
| E2E-A-003 | 首轮对话已落历史 | 承接 E2E-A-002，等待 2s | GET `/api/v1/chat/history?sessionId=...` | 至少 1 条 `role=user` + 1 条 `role=assistant` | P0 | |
| E2E-A-004 | 追加第二轮对话（验证多轮上下文） | 承接 E2E-A-003 | POST stream body `{"sessionId":"...","message":"刚才说的政策，多少天内有效？"}` | SSE 正常返回；不要求语义正确，仅验证多轮请求不报错且历史条数递增 | P1 | 依赖 AI |
| E2E-A-005 | 访客请求转人工 | 承接 E2E-A-004 | POST `/api/v1/chat/transfer` body `{"sessionId":"...","userName":"全流程测试访客","transferReason":"人工咨询","tag":"测试"}` | 200；`status=WAITING` | P0 | 对应 CONV-XFER-002 |
| E2E-A-006 | 会话状态查询同步为 WAITING | 承接 E2E-A-005 | GET `/api/v1/chat/state?sessionId=...` | `status=WAITING` | P0 | |
| E2E-A-007 | 座席上线并出现在在线列表 | 座席 token 已建立 `/api/v1/sessions/events` SSE 长连接 | GET `/api/v1/sessions/agents/online` | 结果含该座席（字段 `id`） | P0 | 对应 CONV-ON-001 |
| E2E-A-008 | 座席接入会话 | 承接 E2E-A-005~007 | POST `/api/v1/sessions/{sessionId}/accept`（座席 token） | 200；`status=ACTIVE`；`agentId=座席ID` | P0 | 对应 CONV-ACC-002 |
| E2E-A-009 | 访客侧状态同步为 ACTIVE | 承接 E2E-A-008 | GET `/api/v1/chat/state?sessionId=...` | `status=ACTIVE` | P0 | |
| E2E-A-010 | ACTIVE 状态下访客经 REST 发消息走固定提示（不调 AI） | 承接 E2E-A-009 | POST `/api/v1/chat/stream` body `{"sessionId":"...","message":"人工接待中我再发一条"}` | SSE 返回固定提示（含"人工客服"字样），不出现 token 分片 | P0 | 不依赖 AI，验证 `resolveStream` 的人工优先分支 |
| E2E-A-011 | 座席通过 WebSocket 发消息，访客收到 | 承接 E2E-A-008；座席端连接 `wss://localhost/ws/agent?token=...`，访客端连接 `wss://localhost/ws/chat/{sessionId}` | 座席端发 `{"type":"MESSAGE","sessionId":"...","content":"座席WS消息-<ts>"}` | 访客端 15s 内收到含该内容的帧 | P0 | 标记 `@pytest.mark.ws`，对应 WS-003 |
| E2E-A-012 | 访客通过 WebSocket 回复，座席收到 | 承接 E2E-A-011 | 访客端发 `{"type":"MESSAGE","content":"访客WS消息-<ts>"}` | 座席端 15s 内收到含该内容的帧 | P0 | 标记 `@pytest.mark.ws` |
| E2E-A-013 | WS 消息与 REST 历史一致 | 承接 E2E-A-011~012 | GET `/api/v1/chat/history?sessionId=...` | 历史含"座席WS消息"和"访客WS消息"两条记录 | P0 | 验证 WS/REST 数据一致性 |
| E2E-A-014 | 座席添加会话备注 | 承接 E2E-A-008 | POST `/api/v1/sessions/{sessionId}/notes` body `{"content":"全流程测试备注"}` | 200 | P1 | |
| E2E-A-015 | 座席打会话标签 | 承接 E2E-A-008，已有标签字典 ID | POST `/api/v1/sessions/{sessionId}/tags` body `{"tagId":N}` | 200；GET 标签列表含该 tagId | P1 | |
| E2E-A-016 | 座席关闭会话 | 承接 E2E-A-008 | POST `/api/v1/sessions/{sessionId}/close`（座席 token） | 200 | P0 | |
| E2E-A-017 | 访客侧状态同步为 CLOSED | 承接 E2E-A-016 | GET `/api/v1/chat/state?sessionId=...` | `status=CLOSED` | P0 | |
| E2E-A-018 | 关闭后异步生成 CSAT 邀请 | 承接 E2E-A-016，等待约 2s | GET `/api/v1/chat/csat/pending?sessionId=...` | `data.csatId` 非空 | P0 | 邀请生成为异步流程 |
| E2E-A-019 | 访客提交满意度评分 | 承接 E2E-A-018 | POST `/api/v1/chat/csat/{csatId}/rate` body `{"score":5,"comment":"全流程测试好评"}` | 200 | P0 | |
| E2E-A-020 | 评分后 pending 查询返回空 | 承接 E2E-A-019 | GET `/api/v1/chat/csat/pending?sessionId=...` | `data` 为空/null | P1 | |
| E2E-A-021 | 完整会话在访客历史中可查 | 承接以上全部 | GET `/api/v1/sessions/visitor-history?visitorName=全流程测试访客`（座席 token） | 结果含该 sessionId，且历史消息数与实际发送轮次一致 | P1 | |
| E2E-A-022 | 完整会话在 Dashboard 近期会话中可查 | 承接以上全部 | GET `/api/v1/dashboard/recent-sessions?limit=10`（座席 token） | 结果含该 sessionId | P2 | 依赖执行顺序产生的数据，建议放在整套用例末尾执行 |

**该场景验证的关键状态机跳转**：`AI_CHAT → WAITING → ACTIVE → CLOSED`，覆盖 `resolveStream` 的三条分支（FAQ 路径 / 人工优先固定提示）、REST 与 WS 双通道数据一致性、CSAT 邀请的异步生成时序。

### 14.2 场景 B：DIT 领域意图路由全链路（关键词匹配 → LLM 分类 → 槏位填充 → 工具调用 → 域切换）

代码依据：
- `ChatAppService.streamDomain`（`DomainSessionAppService.resolveActiveDomain` 确定活跃域 → `MultiIntentService.classifyMulti(message, activeDomain)` 域感知意图分类 → `requiresTransfer` 则转人工，否则走 `DomainAgentService.streamChat`）。
- `DomainSessionAppService`：`resolveOrInitDomain`（首次进入记 `SwitchType.INITIAL`）→ `routeDomainIfNeeded`（ROUTER 小模型判断是否需要切域，记 `SwitchType.ROUTER_MODEL`）。
- `BuiltinTools.switchDomain`：LLM 主动调用 `switch_domain` 工具时触发，记 `SwitchType.LLM_TOOL`；会校验目标域必须在已知域列表中，否则拒绝切换。
- `PendingSlotState`：槏位解析挂起态，`pendingType=MISSING`（等待用户文本输入）或 `DISCOVERED`（等待用户从候选项选择），`retryCount` 达到 `MAX_RETRY=2` 后放弃（预期兜底转人工，需实测确认）。

本场景依赖真实 LLM/Embedding 调用，全部标记 `@pytest.mark.ai`；且需要预先创建测试专用的领域/意图/槏位/工具数据（复用第 12 节 DIT CRUD 接口）。

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| E2E-B-001 | 准备测试领域 | admin token | POST `/api/v1/admin/dit/domains` body `{"code":"e2e_dom_<ts>","name":"全流程测试域","enabled":true,"keywords":"[\"自动化专用词\"]"}` | 200，记录 `domainId` | P1 | `enabled:true`，与 DIT-003（测试用 false）不同，本场景需要域参与真实路由 |
| E2E-B-002 | 准备测试意图（含必填槏位） | 承接 E2E-B-001 | POST `/api/v1/admin/dit/intents` body `{"domainId":domainId,"code":"e2e_intent_<ts>","name":"查询订单","description":"测试意图","autoTransfer":false,"skipRag":true,"keywords":"[\"查订单\",\"订单状态\"]"}` | 200，记录 `intentId` | P1 | `skipRag:true` 避免 RAG 检索干扰断言 |
| E2E-B-003 | 准备必填槏位（仅 ASK_USER 策略，确保必现追问） | 承接 E2E-B-002 | POST `/api/v1/admin/dit/slots` body `{"intentId":intentId,"slotName":"orderId","slotType":"string","description":"订单号","required":true,"resolveStrategy":"[\"ASK_USER\"]","askUserPrompt":"请提供您的订单号，以便查询"}` | 200，记录 `slotId` | P1 | `resolveStrategy` 只含 `ASK_USER`，跳过 EXTRACT/SESSION/DISCOVER 自动解析，保证测试消息必定触发追问 |
| E2E-B-004 | 准备工具与绑定 | 承接 E2E-B-001~002 | POST `/api/v1/admin/dit/tools` 创建 HTTP 工具（同 DIT-030）；POST `/api/v1/admin/dit/bindings` body `{"intentId":intentId,"toolId":toolId,"executionMode":"AUTO","executionOrder":1}` | 均 200 | P1 | |
| E2E-B-005 | 访客初始化并带 domainCode 进入域路径 | 新 anonymousId | POST `/api/v1/chat/session/init`，随后对话请求带 `domainCode` 参数（或走系统默认路由，视前端约定） | 200，记录 sessionId | P1 | `resolveStream` 命中 `StringUtils.isNotBlank(domainCode)` 分支才会进 `streamDomain` |
| E2E-B-006 | 关键词命中触发域初始化 | 承接 E2E-B-005 | POST `/api/v1/chat/stream` body `{"sessionId":"...","message":"自动化专用词","domainCode":"e2e_dom_<ts>"}` | SSE 正常返回（不报错）；审计记录中应出现 `switchType=INITIAL` 一条 | P1 | 依赖 AI；审计记录目前无直接查询接口，需通过 DB 或后续管理接口间接验证，若无可访问入口则本条降级为仅验证 SSE 不报错 |
| E2E-B-007 | 触发意图但缺少必填槏位 → Agent 追问 | 承接 E2E-B-006 | POST stream body `{"sessionId":"...","message":"帮我查订单状态","domainCode":"e2e_dom_<ts>"}` | SSE token 流中出现槏位设定的 `askUserPrompt` 文案（"请提供您的订单号"）或语义等价的追问 | P0 | **核心用例**：验证槏位填充追问机制；依赖 AI，实际文案由 LLM 生成不一定逐字匹配，断言时用关键词包含而非全等 |
| E2E-B-008 | 补充槏位值 → 解析完成并触发工具调用 | 承接 E2E-B-007 | POST stream body `{"sessionId":"...","message":"我的订单号是ORDER20260731001","domainCode":"e2e_dom_<ts>"}` | SSE 正常返回，回复中体现已获取到订单号或工具执行结果（不要求验证工具真实业务结果，仅验证流程未报错、未再次追问同一槏位） | P0 | 依赖 AI + 真实 HTTP 工具调用（`urlTemplate` 指向可达地址，如 DIT-030 用的 `https://nginx/`） |
| E2E-B-009 | 追问重试耗尽后的兜底行为 | 新建一个会话，重复 3 次不提供订单号（每次都用无关内容回复追问） | 连续 3 轮 POST stream，消息均不包含订单号 | 第 3 次（`retryCount` 达到 `MAX_RETRY=2` 后）应有兜底行为（预期转人工或明确提示放弃收集），**需实测记录实际行为**，不预设具体断言 | P2 | **缺陷验证/行为确认用例**：代码只定义了 `shouldGiveUp()`，未在本次调查中确认具体兜底动作，需实测后补充断言并更新本文档 |
| E2E-B-010 | 提出与当前域无关的问题 → LLM 调用 switch_domain 切换 | 承接 E2E-B-008，且系统中至少存在另一个 `enabled=true` 的域（如默认已有域） | POST stream body `{"sessionId":"...","message":"<与当前域完全无关的问题，如询问退货政策>","domainCode":"e2e_dom_<ts>"}` | SSE 流中出现 `event:domainSwitch` 事件（`ChatEvent.domainSwitch`），data 为目标域 code | P1 | 依赖 AI 主动判断调用工具，属于 LLM 行为不确定性较高的用例，多次运行可能不稳定，建议标记 `flaky` 并允许重试 |
| E2E-B-011 | 域切换到不存在的域被拒绝（边界） | - | 无法直接从 REST 层伪造 LLM 工具调用参数，此用例建议在 Java 单元测试层面覆盖（`BuiltinTools.switchDomain` 传入不存在的 `targetDomainCode`），黑盒接口测试不覆盖 | 不适用（标注为单元测试覆盖范围） | P2 | 说明性条目，非可执行的接口用例 |
| E2E-B-012 | 域路径下投诉/转人工意图仍可转人工 | 新会话，创建一个 `autoTransfer:true` 的意图（域为 e2e_dom） | POST stream body 命中该意图关键词 | SSE 返回 TRANSFER 语义事件，会话状态变为 `WAITING`（对应 `handleTransfer`） | P1 | 验证域路径与 FAQ 路径共用 `handleTransfer` 逻辑 |
| E2E-B-013 | 清理测试数据 | 承接 E2E-B-001~004 | 按顺序 DELETE：`/bindings/{id}` → `/slots/{id}` → `/intents/{id}` → `/tools/{id}` → `/domains/{id}` | 均 200 | P2 | 清理顺序错误会导致外键约束报错或残留脏数据，见附录清理注意事项 |

**已知不确定性，需实测后回填本文档**：
1. E2E-B-006 的域切换审计记录（`cs_session_domain_switch` 表或类似结构）目前没有确认对外的查询接口，本文档调查阶段只看到写入逻辑（`SessionDomainSwitchRepository.record`），未定位到对应的 GET 接口。若确认无接口可查，此断言应降级或改为直连 DB 校验。
2. E2E-B-009 的槏位追问重试耗尽后的具体兜底动作（是否转人工/是否有专门提示语）需要实测确认，代码里只看到 `shouldGiveUp()` 判断方法，未追踪到调用它之后具体做什么。
3. E2E-B-010 依赖 LLM 主动决策调用工具，非确定性较高，实现 pytest 用例时建议允许重试或降低严格度（如只断言"最终域是否变化"而非"是否恰好一次调用"）。

---

## 附：本文件涉及的清理注意事项

- SSE 长连接（`/sessions/events`）测试后必须显式 kill 后台进程/关闭连接，避免遗留进程占用座席在线状态。
- 业务时间排班测试（BH-系列）**存在破坏性风险**（全周停业会影响真实业务判断），任何自动化实现都必须在测试前备份当前排班（`GET /schedule` 落盘），测试后立即恢复，并在异常退出时兜底恢复（对齐现有 `api-autotest.sh` 的 `trap cleanup EXIT` 设计）。
- DIT 清理顺序：绑定 → 槽位 → 意图 → 工具 → 领域，顺序错误可能导致外键约束报错或残留脏数据。
- SLA 违规实测（SLA-社区实测用例）耗时约 75 秒，建议标记 `@pytest.mark.slow`，可通过 `-m "not slow"` 跳过。
