# 数据库快照

从当前开发库导出的表结构与业务基础数据快照，用于新环境快速搭建。

## 文件说明

| 文件 | 数据库 | 内容 |
|------|--------|------|
| `ai_customerservice-schema.sql` | `ai_customerservice` | 表结构（含 `cs_auth`、`cs_conversation` 两个 schema、序列、索引、外键） |
| `ai_customerservice-data.sql` | `ai_customerservice` | 业务基础数据（详见下方"数据范围"） |
| `ai_knowledge-schema.sql` | `ai_knowledge` | 表结构（`public` schema：`knowledge_kb / knowledge_doc / knowledge_chunk`） |
| `ai_knowledge-data.sql` | `ai_knowledge` | 知识库数据（`knowledge_kb` 3 条种子数据，其他表为空） |

## 数据范围

### 已导出（业务基础数据）

`ai_customerservice`：
- **cs_auth**：`sys_user / sys_role / sys_menu / sys_permission / sys_dept / *_role_*` 关联表 + `ai_model_config`
- **cs_conversation**：`cs_domain`（4）、`cs_intent`（17）、`cs_intent_slot`（11）、`cs_intent_tool`（10）、`cs_tool`（9）
- 各 schema 的 `flyway_schema_history`（保证 Flyway 认为迁移已应用）

`ai_knowledge`：`knowledge_kb`（3）+ `flyway_schema_history`

### 未导出（运行时会话数据，仅结构不含数据）

`cs_conversation` schema 下的运行时表：
- `cs_conversation` — 会话记录
- `cs_conversation_message` — 会话消息
- `cs_pending_slot` — 槽位缓存
- `cs_tool_call_log` — 工具调用日志

## 还原步骤

前置：postgres 已运行，`cs-postgres` 容器名（docker-compose 默认）或直接连本地 5432。

```bash
# 1. 创建数据库（若首次）
docker exec cs-postgres psql -U postgres -c "CREATE DATABASE ai_customerservice"
docker exec cs-postgres psql -U postgres -c "CREATE DATABASE ai_knowledge"

# 2. 导入表结构
docker exec -i cs-postgres psql -U postgres -d ai_customerservice < docs/sql/ai_customerservice-schema.sql
docker exec -i cs-postgres psql -U postgres -d ai_knowledge      < docs/sql/ai_knowledge-schema.sql

# 3. 导入业务基础数据
docker exec -i cs-postgres psql -U postgres -d ai_customerservice < docs/sql/ai_customerservice-data.sql
docker exec -i cs-postgres psql -U postgres -d ai_knowledge      < docs/sql/ai_knowledge-data.sql
```

## 重新导出（生成新快照）

改动 schema 或补充业务基础数据后，重新导出：

```bash
cd docs/sql

# schema
docker exec cs-postgres pg_dump -U postgres -d ai_customerservice \
  --schema-only --no-owner --no-privileges \
  > ai_customerservice-schema.sql
docker exec cs-postgres pg_dump -U postgres -d ai_knowledge \
  --schema-only --no-owner --no-privileges \
  > ai_knowledge-schema.sql

# data（排除 cs_conversation 运行时会话表）
docker exec cs-postgres pg_dump -U postgres -d ai_customerservice \
  --data-only --no-owner --no-privileges --column-inserts \
  --exclude-table=cs_conversation.cs_conversation \
  --exclude-table=cs_conversation.cs_conversation_message \
  --exclude-table=cs_conversation.cs_pending_slot \
  --exclude-table=cs_conversation.cs_tool_call_log \
  > ai_customerservice-data.sql
docker exec cs-postgres pg_dump -U postgres -d ai_knowledge \
  --data-only --no-owner --no-privileges --column-inserts \
  > ai_knowledge-data.sql
```
