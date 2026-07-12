# 数据库快照

从当前开发库导出的表结构与业务基础数据快照，用于新环境快速搭建。

## 文件说明

| 文件 | 数据库 | 内容 |
|------|--------|------|
| `aria_cs-schema.sql` | `aria_cs` | 表结构（含 `cs_auth`、`cs_conversation` 两个 schema、序列、索引、外键） |
| `aria_cs-data.sql` | `aria_cs` | 业务基础数据（详见下方"数据范围"） |
| `aria_knowledge-schema.sql` | `aria_knowledge` | 表结构（`public` schema：`knowledge_kb / knowledge_doc / knowledge_chunk`） |
| `aria_knowledge-data.sql` | `aria_knowledge` | 知识库数据（`knowledge_kb` 3 条种子数据，其他表为空） |

## 数据范围

### 已导出（业务基础数据）

`aria_cs`：
- **cs_auth**：`sys_user / sys_role / sys_menu / sys_permission / sys_dept / *_role_*` 关联表 + `ai_model_config`
- **cs_conversation**：`cs_domain`（4）、`cs_intent`（17）、`cs_intent_slot`（11）、`cs_intent_tool`（10）、`cs_tool`（9）
- 各 schema 的 `flyway_schema_history`（保证 Flyway 认为迁移已应用）

`aria_knowledge`：`knowledge_kb`（3）+ `flyway_schema_history`

### 未导出（运行时会话数据，仅结构不含数据）

`cs_conversation` schema 下的运行时表：
- `cs_conversation` — 会话记录
- `cs_conversation_message` — 会话消息
- `cs_pending_slot` — 槽位缓存
- `cs_tool_call_log` — 工具调用日志

## 还原步骤

前置：postgres 已运行，容器名 `ai-cs-postgres`（docker-compose 默认）或直接连本地 5432。

```bash
# 1. 创建数据库（若首次）
docker exec ai-cs-postgres psql -U postgres -c "CREATE DATABASE aria_cs"
docker exec ai-cs-postgres psql -U postgres -c "CREATE DATABASE aria_knowledge"

# 2. 导入表结构
docker exec -i ai-cs-postgres psql -U postgres -d aria_cs       < docs/sql/aria_cs-schema.sql
docker exec -i ai-cs-postgres psql -U postgres -d aria_knowledge < docs/sql/aria_knowledge-schema.sql

# 3. 导入业务基础数据
docker exec -i ai-cs-postgres psql -U postgres -d aria_cs       < docs/sql/aria_cs-data.sql
docker exec -i ai-cs-postgres psql -U postgres -d aria_knowledge < docs/sql/aria_knowledge-data.sql
```

## 现有数据库重命名（已有旧库时执行）

```bash
docker exec ai-cs-postgres psql -U postgres \
  -c "ALTER DATABASE ai_customerservice RENAME TO aria_cs;" \
  -c "ALTER DATABASE ai_knowledge RENAME TO aria_knowledge;"
```

## 重新导出（生成新快照）

改动 schema 或补充业务基础数据后，重新导出：

```bash
cd docs/sql

# schema
docker exec ai-cs-postgres pg_dump -U postgres -d aria_cs \
  --schema-only --no-owner --no-privileges \
  > aria_cs-schema.sql
docker exec ai-cs-postgres pg_dump -U postgres -d aria_knowledge \
  --schema-only --no-owner --no-privileges \
  > aria_knowledge-schema.sql

# data（排除 cs_conversation 运行时会话表）
docker exec ai-cs-postgres pg_dump -U postgres -d aria_cs \
  --data-only --no-owner --no-privileges --column-inserts \
  --exclude-table=cs_conversation.cs_conversation \
  --exclude-table=cs_conversation.cs_conversation_message \
  --exclude-table=cs_conversation.cs_pending_slot \
  --exclude-table=cs_conversation.cs_tool_call_log \
  > aria_cs-data.sql
docker exec ai-cs-postgres pg_dump -U postgres -d aria_knowledge \
  --data-only --no-owner --no-privileges --column-inserts \
  > aria_knowledge-data.sql
```
