# 数据库初始化

`init.sql` 是全量数据库初始化脚本（结构 + 业务种子数据），用于新环境从零搭建。

## 文件说明

| 文件 | 内容 |
|------|------|
| `init.sql` | 全量初始化：创建 `aria_cs` / `aria_knowledge` 两个库、所有 schema/扩展/表/索引/外键/触发器，以及业务种子数据 |

脚本内覆盖两个数据库：

- **aria_cs** —— schema `cs_auth`（认证/权限/系统配置）、`cs_conversation`（会话/意图/工具/SLA）；扩展 `pgcrypto`
- **aria_knowledge** —— schema `public`（知识库/文档/切片）；扩展 `pg_jieba`、`vector`

执行方式：docker-entrypoint 首次初始化时对 `POSTGRES_DB=aria_cs` 执行本脚本，脚本内部再显式 `CREATE DATABASE aria_knowledge` 并 `\connect` 切换完成第二个库。

## 种子数据范围

### 包含（系统运行所需的基础配置）

`aria_cs`：
- **cs_auth**：`sys_menu`(72) / `sys_permission`(31) / `sys_role`(3：super_admin/kf_manager/kf_staff) / 三个标准账号 `sys_user`(3：superadmin/kfmanager/kfstaff) / `sys_role_menu` / `sys_role_permission` / `sys_role_data_scope` / `sys_user_role` / `system_config`(14 项系统配置)
- **cs_conversation**：`cs_domain`(4：电商/金融/酒旅/天气) / `cs_intent`(18) / `cs_intent_slot`(11) / `cs_intent_tool`(10) / `cs_tool`(9) / `cs_sla_policy`(1) / `cs_business_hours_schedule`(7) / `cs_tag`(3 预置标签)

`aria_knowledge`：`knowledge_kb`(3：default/faq/ticket)

### 不包含

- **ai_model_config**：AI 模型配置含真实 API Key 与厂商端点，不纳入种子，由部署方在后台自行配置。
- **运行时业务数据**：会话（`cs_conversation`）、消息（`cs_conversation_message`）、SLA 违约（`cs_sla_breach`）、CSAT 评价、知识文档/切片（`knowledge_doc` / `knowledge_chunk`）等运行期产生的数据均为空表。
- **测试污染数据**：`e2e_*` / `autotest_*` / `自动化*` 等自动化测试产生的域、意图、快捷回复、标签、配置等已剔除。

## 从零初始化（docker-compose）

`init.sql` 已由 compose 挂载到 postgres 容器的 `/docker-entrypoint-initdb.d/`，**首次启动空数据目录时自动执行**：

- 本地：`deploy/docker-compose-local.yml` 挂载 `../docs/sql/init.sql`
- 生产：`deploy/docker-compose.yml` 挂载 `/root/ai-cs/init.sql`（部署前将本文件复制到该路径）

```bash
cd deploy
docker compose -f docker-compose-local.yml up -d postgres
```

> 注意：docker-entrypoint 仅在数据目录为空时执行初始化脚本。已有数据的库不会重复执行，需手动导入。

## 手动导入（已有 postgres 实例）

```bash
docker exec -i ai-cs-postgres psql -U postgres -d postgres < docs/sql/init.sql
```

脚本从 `aria_cs` 库开始（`\connect aria_cs`），随后创建并切换到 `aria_knowledge`，一次执行完成两个库。

## 重新生成 init.sql（schema 或种子变更后）

改动表结构或补充业务种子后，从开发库重新导出并组装。核心原则：

1. **结构**：`pg_dump --schema-only --no-owner --no-privileges` 分别导出 `aria_cs`、`aria_knowledge`。
2. **种子**：先在 scratch 库还原全量数据，用 SQL 剔除测试污染（`e2e_*`/`autotest_*`/`自动化*`）与运行时数据，再 `pg_dump --data-only --no-owner --no-privileges` 导出干净种子（含 setval 序列同步）。
3. **组装**：按 `\connect aria_cs` → cs 结构 → cs 种子 → `CREATE DATABASE aria_knowledge` → `\connect aria_knowledge` → knowledge 结构 → knowledge 种子 的顺序拼接。**原样拼接**各 dump 片段，勿逐行处理（多行字符串值会被破坏）。
4. **验证**：用全新 postgres 容器（挂载 pg_jieba 扩展）从零执行一遍，确认无 ERROR/FATAL 且两库种子行数符合预期。
