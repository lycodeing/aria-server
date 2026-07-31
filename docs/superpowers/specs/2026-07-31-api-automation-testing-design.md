# 接口自动化测试设计文档

日期：2026-07-31
状态：已确认

## 1. 背景与目标

ARIA 项目由三个独立微服务组成：`auth-service`（8083）、`conversation-service`（8082，多实例）、`knowledge-service`（8081），通过 nginx 网关统一暴露在 `https://localhost/{auth,conversation,knowledge}/...`。

现状：
- `deploy/api-autotest.sh`（900+ 行 bash）+ `deploy/ws-autotest.py` 已经针对本地 docker-compose 部署栈跑过一轮非常详尽的黑盒测试，覆盖了 conversation-service 的大部分状态机、幂等、越权、SLA 实测、WS 双向通信场景。
- auth-service、knowledge-service 的 Controller 层几乎没有专门的接口级自动化覆盖。
- 没有结构化的测试用例文档，现有覆盖范围只能通过读脚本源码得知。
- 没有可长期维护、可增量扩展的测试代码资产（bash 脚本适合一次性验证，不适合长期回归）。

目标：
1. 产出一份完整的**接口自动化测试用例文档**，覆盖三个服务的全部业务接口，包括正向流程、边界条件、状态机、权限校验、已知代码缺陷验证。
2. 基于用例文档，用 **Python + pytest** 重新实现一套可维护、可增量扩展的黑盒接口自动化测试项目，直接对着本地 `docker-compose-local.yml` 起的真实环境（`https://localhost`）跑，复用现有种子测试账号，不引入 Testcontainers。
3. 本次先交付测试用例文档，用户确认后再实现 pytest 项目骨架与用例代码。

## 2. 范围

覆盖三个服务的全部 REST 接口（含内部 `/internal/**` 接口）、SSE 流式接口、WebSocket 双向通信。不覆盖：
- 前端 UI 层测试。
- 性能/压力测试。
- 单元测试层面的覆盖（已有 JUnit 测试保留不动，本次是接口黑盒层）。

## 3. 测试用例文档设计

### 3.1 目录结构

```
docs/testing/api-test-cases/
  README.md                     # 总览、环境说明、账号清单、运行方式
  auth-test-cases.md            # 登录/用户/角色/菜单/AI模型/系统配置/内部接口
  conversation-test-cases.md    # 访客会话/对话/短信认证/转人工队列/CSAT/
                                 # 备注标签/快捷回复/SLA/Webhook/业务时间/
                                 # Dashboard/DIT/WebSocket
  knowledge-test-cases.md       # 文档摄取/审核/Chunk/检索/QA/翻译/内部接口
```

### 3.2 用例格式

每条用例采用统一表格字段：

| 字段 | 说明 |
|---|---|
| ID | 模块前缀+编号，如 `AUTH-001` |
| 标题 | 一句话描述 |
| 前置条件 | 需要的账号/数据状态 |
| 步骤 | 具体请求（方法+路径+关键参数） |
| 预期结果 | HTTP 状态码 + 业务码（若适用）+ 关键响应字段断言 |
| 优先级 | P0（核心链路）/ P1（重要分支）/ P2（边界/低频） |
| 备注 | 依赖 AI/耗时较长/已知缺陷/幂等性说明等 |

已发现的代码缺陷（例如密码历史校验疑似失效、角色停用系统角色抛 500 而非 400、`review(approved=false)` 在 DRAFT 状态下非法转换、`offline()` 不物理删除 chunk、角色/菜单接口缺少权限校验等）单独标注为「缺陷验证用例」，预期结果按**代码当前实际行为**记录，并注明这是已知问题而非设计预期，避免测试代码把 bug 当成规范固化。

### 3.3 覆盖内容大纲

**auth-test-cases.md**
- 登录：正确/错误密码、用户名不存在与密码错误返回一致错误码、IP 限流（10次/分钟）、失败次数锁定（5次锁定30分钟）、锁定自动解锁、密码过期标记 `mustChangePassword`、rememberMe 超时差异
- 登出、Token 刷新（不保留 rememberMe）
- 用户管理：CRUD、启用/禁用（不主动踢下线）、硬删除、禁止自删、改密码（历史校验缺陷验证）、重置密码（不查历史）、分配角色（不校验 roleId 存在性）、`/me/*` 未实现子接口返回 501
- 角色管理：CRUD、系统角色改名/停用异常路径（500 而非 400）、删除有用户关联角色的脏数据风险、权限树、数据域设置（不校验 scopeType 合法性）、**无权限限制缺陷**
- 菜单管理：路由树过滤 BUTTON、权限码提取、删除有子菜单校验、递归深度截断、**无权限限制缺陷**
- AI 模型配置：CRUD、同类型默认唯一性、默认配置禁止删除、测试连接（真实HTTP调用，RERANKER 为 mock）、API Key 脱敏
- 系统配置：CRUD、SYSTEM 类型仅 super_admin 可操作、configKey 格式校验、get map/value 语义（不存在返回默认值而非404）
- 内部接口：X-Internal-Secret 校验（含 fail-secure、恒定时间比较、错误体字段名 `message`）、token verify、AI模型/系统配置内部查询

**conversation-test-cases.md**
- 访客会话初始化：`X-Anonymous-Id` 校验、首次/复用语义、CLOSED 后重建
- 对话：流式/非流式、空消息、非法 sessionId、历史全量/增量/清除、消息反馈状态机（up/down/取消/幂等）
- 短信认证：格式校验、60s 限流、验证码一次性、5次错误锁定、手机号脱敏
- 转人工与队列：幂等转人工、accept/close/transfer 状态机、归属 CAS、目标在线校验、WAITING 不可转交、非服务时间拦截
- 在线座席、SSE 事件流（握手、推送、无效 token 拒绝）
- 备注/标签：CRUD、权限、跨账号隔离
- 快捷回复：管理端公共模板 + 坐席私人模板隔离、搜索语义（整词匹配）
- SLA：策略 CRUD、参数校验、权限、违规实测（真实等待触发）
- Webhook：CRUD、HTTPS 强制、测试发送
- 业务时间：排班查询/更新、节假日 CRUD、离线回复（已知 stub 未实现）、全周停业拦截演练
- Dashboard：13 个统计接口 + 参数化 + 鉴权
- DIT：领域/意图/槽位/工具/绑定全链路 CRUD、正则校验
- WebSocket：访客⇄座席双向消息、TYPING 转发、无 sessionId 消息丢弃、无 token/非法 sessionId 拒绝

**knowledge-test-cases.md**
- 文档上传：文件类型按后缀推断（未知后缀落 MARKDOWN，不报错）、超大文件无语义化错误（500而非400）
- 摄入状态机：真实状态只有 DRAFT/REVIEW/PUBLISHED/DEPRECATED/FAILED，质量过滤全部不合格直接 FAILED（不进DLQ）、MQ 3次重试耗尽进DLQ标记FAILED、retry(仅FAILED→DRAFT) vs reingest(仅PUBLISHED，强制跳过终态校验) 语义区分
- 审核：REVIEW→PUBLISHED/DRAFT、DRAFT→DRAFT 非法转换（500缺陷验证）、并发审核冲突
- 下线：单个 offline（不删chunk，缺陷验证）vs batch-offline（过滤非PUBLISHED静默跳过，上限50条）
- Chunk 管理：disable/enable 对检索的影响（retrieval_weight>0过滤）、updateContent 重新向量化
- 检索：向量+全文并行召回、RRF融合、reranker 可选降级、超时降级、topK 边界（internal ≤50 / management ≤20）
- QA：手动录入、docStatus 硬编码 PUBLISHED 不校验文档真实状态（缺陷验证）、不校验 docId/kbId 存在性
- 翻译：`ConditionalOnProperty` 默认关闭需环境确认、异常分支 HTTP 200 但业务码500（缺陷验证）
- 文档预览/统计：Content-Type 映射、文件名转义、`kb-stats` vs `stats` 的 weight 过滤差异
- 内部接口：search/rerank 鉴权、参数校验

### 3.4 已知代码缺陷清单（附录）

在 README.md 中汇总所有本次调查发现的疑似缺陷/不一致点（约 10 余项，详见上文各模块「缺陷验证用例」），标注对应测试用例 ID，供开发团队后续决定是修复还是确认为预期行为。

## 4. pytest 项目设计（文档确认后落地，本次不实现）

- 位置：顶层 `api-tests/`，独立 `pyproject.toml`，不进 Maven 构建。
- 目标环境：直连 `docker-compose-local.yml` 已起的 nginx 网关 `https://localhost`，`base_url` 可用环境变量覆盖；不用 Testcontainers。
- 账号：复用现有种子账号（superadmin/kfmanager/kfstaff，密码 `Test@123456`）。
- 结构：`conftest.py`（base_url/token/清理 fixture）+ `clients/`（三服务的轻量 HTTP 客户端）+ `tests/{auth,conversation,knowledge}/`，测试文件按用例文档的模块划分对齐。
- 测试数据：时间戳后缀保证幂等可重复执行，每个测试自行 teardown 清理，不依赖执行顺序。
- SSE：`httpx` 流式响应逐行断言。
- WebSocket：`pytest-asyncio` + `websockets` 重写 `ws-autotest.py` 场景，标记 `@pytest.mark.ws`。
- 标记：`slow`（SLA 实测等耗时用例）、`ai`（依赖真实 LLM/Embedding）、`ws`（WebSocket），支持 `-m "not ai"` 等组合跳过，与现有 bash 脚本的 `SKIP_AI`/`SKIP_SLOW` 语义对齐。

## 5. 交付顺序

1. 本次：撰写并确认测试用例文档（`docs/testing/api-test-cases/`）。
2. 后续（用户确认文档后）：搭建 `api-tests/` pytest 项目骨架，按文档用例逐模块实现测试代码，替换/退役现有 bash 脚本的等效场景。
