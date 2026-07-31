# ARIA 接口自动化测试用例文档

日期：2026-07-31
关联设计文档：[docs/superpowers/specs/2026-07-31-api-automation-testing-design.md](../../superpowers/specs/2026-07-31-api-automation-testing-design.md)

## 1. 文档结构

| 文件 | 覆盖模块 | 用例数 |
|---|---|---|
| [auth-test-cases.md](./auth-test-cases.md) | 登录鉴权、用户管理、角色管理、菜单管理、AI 模型配置、系统配置、内部接口 | 135 |
| [conversation-test-cases.md](./conversation-test-cases.md) | 访客会话、对话、短信认证、转人工队列、SSE 事件流、CSAT、备注/标签、快捷回复、SLA、Webhook、业务时间、Dashboard、DIT、WebSocket、**全流程端到端场景（第14节）** | 205 |
| [knowledge-test-cases.md](./knowledge-test-cases.md) | 文档上传/摄入状态机、审核、下线、Chunk 管理、混合检索、QA 录入、翻译、预览统计、内部接口 | 73 |
| **合计** | | **413** |

其中 `conversation-test-cases.md` 第 14 节「全流程端到端场景」把此前分散在各节的用例串成两条完整业务链路：
- **场景 A**：访客 init → AI/FAQ 多轮对话 → 转人工 WAITING → 座席 accept(ACTIVE) → REST+WS 双向消息 → 备注/标签 → close(CLOSED) → 生成 CSAT 邀请 → 评分 → 历史/Dashboard 数据可查，全程用同一个 sessionId 断言状态机每一步转换。
- **场景 B**：DIT 领域/意图路由全链路——关键词匹配 → LLM 分类 → 槏位缺失追问（`PendingSlotState`，MISSING/DISCOVERED，重试上限 `MAX_RETRY=2`）→ 工具调用 → `switch_domain`/`transfer_to_agent` 内置工具触发的跨域切换与转人工。依赖真实 LLM 判断的用例已标注 `flaky`，行为未在本次调查中最终确认的追问兜底逻辑标注为「缺陷验证/行为确认用例」。

## 2. 测试环境

- 目标环境：本地 `deploy/docker-compose-local.yml` 起的完整栈（Postgres+pgvector、Redis、RabbitMQ、MinIO、三个业务服务、nginx 网关）。
- 统一入口：`https://localhost`（自签证书，客户端需 `-k`/关闭证书校验），网关按路径前缀转发：
  - `https://localhost/auth/...` → auth-service:8083
  - `https://localhost/conversation/...` → conversation-service:8082（双实例，`ip_hash` 会话粘滞）
  - `https://localhost/knowledge/...` → knowledge-service:8081
  - `wss://localhost/ws/...` → conversation-service（WebSocket）
- 内部接口密钥：`X-Internal-Secret: aria-internal-lycodeing-2024`（`aria.internal.secret` 配置项，仅用于内部服务间接口测试）。

## 3. 测试账号

| 用户名 | 密码 | 角色 | 用途 |
|---|---|---|---|
| `superadmin` | `Test@123456` | super_admin | 覆盖全部管理接口、权限上限验证 |
| `kfmanager` | `Test@123456` | 客服管理员 | SLA/Dashboard 等管理场景的中间权限验证 |
| `kfstaff` | `Test@123456` | kf_staff（普通客服） | 权限下限验证、越权拒绝断言、私有数据隔离验证 |

访客身份无需登录，通过 `X-Anonymous-Id`（≥8位，仅允许字母数字下划线中横线）或短信认证 token 标识。

## 4. 用例设计约定

- 用例 ID 前缀：`AUTH-xxx` / `CONV-<子模块>-xxx` / `KNOW-xxx`，编号仅保证模块内唯一，不代表执行顺序依赖。
- 优先级：P0（核心链路，必须每次回归）/ P1（重要分支/状态机边界）/ P2（低频边界、已知次要缺陷）。
- 测试数据统一带时间戳后缀（如 `autotest_<ts>`），保证并发/重复执行不冲突；每类用例的清理方式在对应文档的前置条件/备注中说明。
- 依赖真实 LLM/Embedding 调用的用例在备注标注「依赖 AI」，对应 pytest 阶段会标记 `@pytest.mark.ai`，可用 `-m "not ai"` 跳过（语义对齐现有 `deploy/api-autotest.sh` 的 `SKIP_AI=1`）。
- 耗时较长的用例（如 SLA 违规实测需真实等待 75s）标注「耗时」，对应标记 `@pytest.mark.slow`（对齐 `SKIP_SLOW=1`）。
- WebSocket 用例标注「WS」，对应标记 `@pytest.mark.ws`。

## 5. 已知代码缺陷清单（汇总）

以下是三轮代码调查中发现的疑似缺陷/设计不一致点，已在各模块文档中以「缺陷验证用例」的形式固化为具体测试步骤。这些用例的预期结果按**代码当前实际行为**编写，测试目的是**验证并记录现状**，不代表这是期望的规范行为。后续开发团队可据此决定修复或明确接受为已知限制。

### auth-service

| 用例 ID | 问题描述 | 影响 |
|---|---|---|
| AUTH-031 | 用户名含中文/特殊字符时，DTO 校验（`@Size(3,50)`）与领域层正则（`^[A-Za-z0-9_.-]{3,50}$`）不一致，领域层抛出的 `IllegalArgumentException` 未包装为 `BusinessException`，导致返回 HTTP 500 而非 400 | 错误响应语义不正确，客户端无法区分参数错误与服务端故障 |
| AUTH-044 | `User.changePassword()` 校验历史密码时调用 `hasher.matches(newPwd.hash(), oldHash)`，疑似把新密码的哈希值当作明文传入 `matches()`，可能导致密码历史校验实质失效 | **安全相关**：用户可能可以"改回"最近用过的旧密码，绕过历史校验意图 |
| AUTH-062 | `RoleController` 全部接口仅 `@SaCheckLogin`，无 `@SaCheckPermission`/`@SaCheckRole`，任意登录用户（包括普通客服）可创建/修改/删除角色 | **安全缺陷**：权限管理功能本身缺少权限保护 |
| AUTH-068/069 | 系统角色（`isSystem=true`）改名/停用时，领域层抛 `IllegalStateException` 未包装，预期返回 HTTP 500 而非语义化 400 | 错误响应语义不正确 |
| AUTH-073 | 删除角色不检查是否有用户关联，`sys_user_role` 关联行不会被级联清理，可能残留脏数据 | 数据一致性问题 |
| AUTH-080 | `setDataScope` 不校验 `scopeType` 取值合法性，任意字符串都会被接受存入 | 数据校验缺失 |
| AUTH-086 | `MenuController` 同样无权限校验，任意登录用户可增删改菜单树 | **安全缺陷**，与 AUTH-062 同类问题 |
| AUTH-087 | 创建菜单缺少 `menuName` 时，领域层异常未包装，预期 HTTP 500 而非 400 | 错误响应语义不正确 |
| AUTH-106 | AI 模型测试连接对 `ROUTER` 类型未做特殊处理，会走 `CHAT` 分支调用 `/chat/completions`，语义可能不符合 ROUTER 模型的实际用途 | 功能语义存疑，需人工判断是否为预期行为 |

### conversation-service

| 用例 ID | 问题描述 | 影响 |
|---|---|---|
| CONV-BH-016 | 离线回复更新接口（`PUT /admin/business-hours/offline-reply`）为 stub 实现（代码 TODO：待 AuthClient 支持写操作），调用返回 200 但写入可能不生效 | 已知未实现功能，非阻断性缺陷 |

### knowledge-service

| 用例 ID | 问题描述 | 影响 |
|---|---|---|
| KNOW-006 | 文档上传对未知/不支持的文件后缀无拒绝逻辑，一律按 MARKDOWN 处理，不返回 400 | 缺少输入校验，可能导致乱码/无意义的摄入结果 |
| KNOW-007 | 超出 Spring 默认 multipart 大小限制时，`MaxUploadSizeExceededException` 未被专门捕获，兜底走全局异常处理器返回 HTTP 500，而非语义化的 400/413 | 错误响应语义不正确 |
| KNOW-020 | 文档处于 DRAFT 状态时调用 `review(approved=false)`，因 DRAFT→DRAFT 不在合法流转范围内，触发状态机异常（业务码 5010），是容易被忽略的边界 | 需要产品/开发确认此分支的预期行为 |
| KNOW-026 | `offline()` 只将文档状态改为 DEPRECATED，不会物理删除关联 chunk，与 Controller 注释"物理删除所有chunk"的描述不符 | 文档与实现不一致；虽不影响检索结果（检索按 doc_status 过滤），但存在数据表膨胀风险 |
| KNOW-053 | 手动录入的 QA chunk 的 `docStatus` 被硬编码为 `PUBLISHED`，与所属文档的真实状态无关，导致文档尚未发布时其 QA chunk 已可被检索 | 数据一致性问题 |
| KNOW-054 | `addQA` 不校验传入的 `docId`/`kbId` 是否真实存在，会成功插入一条游离的 chunk | 数据完整性缺失 |
| KNOW-061 | 翻译接口内部异常时，Controller 直接 `catch` 并返回 `R.fail(500, ...)`，未经过全局异常处理器，导致 **HTTP 状态码是 200**，业务码才是 500，与其他接口"异常经全局处理器返回对应 HTTP 状态码"的行为不一致 | 客户端若只按 HTTP 状态码判断成功/失败会误判为成功 |

## 6. 与现有脚本的关系

`deploy/api-autotest.sh` + `deploy/ws-autotest.py`（900+ 行 bash + Python）已经对 conversation-service 的绝大部分场景做过手工验证，本次用例文档在其基础上：
1. 结构化整理为可追踪的用例表格（原脚本的断言逻辑分散在代码中，不便于评审和增量维护）；
2. 补充了 auth-service、knowledge-service 此前完全没有接口级测试覆盖的部分；
3. 新增了本次代码调查发现的缺陷验证用例。

用例文档确认后，将按 [设计文档](../../superpowers/specs/2026-07-31-api-automation-testing-design.md) 第 4 节的方案在 `api-tests/` 下用 pytest 重新实现，逐步替换现有 bash 脚本的等效场景。

## 7. 下一步

请复核以上三个模块的用例文档，重点关注：
- 是否有业务规则理解错误或遗漏的接口；
- 第 5 节缺陷清单中的问题是否需要调整优先级，或有你认为应排除/新增的项；
- 用例粒度是否合适（过细/过粗）。

确认后即可开始搭建 `api-tests/` pytest 项目骨架。
