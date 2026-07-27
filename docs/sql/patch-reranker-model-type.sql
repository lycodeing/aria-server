-- =============================================================================
-- 补丁：新增 RERANKER 模型类型支持
-- 分支：refactor/mapper-enum-ddd-cleanup
-- 日期：2026-07-27
--
-- 背景：ai_model_config 表原 CHECK 约束仅允许 CHAT / EMBEDDING / ROUTER 三种
--       model_type 值。本补丁扩展约束以支持 RERANKER（BGE-Reranker 精排模型），
--       并插入默认的本地 BGE-Reranker-v2-M3 配置行。
--
-- 适用场景：
--   - 已有部署（非 Docker 全量初始化）执行本脚本完成增量升级
--   - Docker 全量初始化已由 init-db.sql 包含此变更，无需重复执行
--
-- 执行前提：
--   - 以具有 cs_auth schema DDL 权限的用户执行
--   - 建议在维护窗口或低峰期执行（ALTER TABLE 短暂持有 AccessExclusiveLock）
--
-- 幂等性：
--   - DROP/ADD CONSTRAINT 前先判断约束是否存在（psql \d cs_auth.ai_model_config 可确认）
--   - INSERT 使用 ON CONFLICT (id) DO NOTHING 防止重复插入
--   - SELECT setval 使用 MAX(id) 保证自增序列安全推进
-- =============================================================================

BEGIN;

-- ---------------------------------------------------------------------------
-- 1. 扩展 model_type CHECK 约束，加入 RERANKER
-- ---------------------------------------------------------------------------
ALTER TABLE cs_auth.ai_model_config
    DROP CONSTRAINT IF EXISTS ai_model_config_model_type_check;

ALTER TABLE cs_auth.ai_model_config
    ADD CONSTRAINT ai_model_config_model_type_check
        CHECK (model_type = ANY (ARRAY[
            'CHAT'::character varying,
            'EMBEDDING'::character varying,
            'ROUTER'::character varying,
            'RERANKER'::character varying
        ]));

-- ---------------------------------------------------------------------------
-- 2. 更新 model_type 列注释
-- ---------------------------------------------------------------------------
COMMENT ON COLUMN cs_auth.ai_model_config.model_type
    IS 'CHAT=对话大模型, EMBEDDING=向量模型, ROUTER=域路由小模型, RERANKER=重排序模型';

-- ---------------------------------------------------------------------------
-- 3. 插入默认 RERANKER 配置（本地 BGE-Reranker-v2-M3）
--    ON CONFLICT DO NOTHING 保证幂等，重复执行不报错
-- ---------------------------------------------------------------------------
INSERT INTO cs_auth.ai_model_config
    (id, name, provider, api_protocol, remark,
     base_url, api_key_enc, model_name,
     temperature, max_tokens, timeout_sec,
     is_default, is_enabled, created_by,
     created_at, updated_at, deleted_at, model_type)
VALUES
    (11,
     '本地 BGE-Reranker-v2-M3',
     'Custom',
     'OPENAI_COMPATIBLE',
     'BGE-Reranker-v2-M3 精排模型，基于 Cross-Encoder 架构，专为中英双语优化。'
     '部署方式：infinity-emb 或 Xinference，接口兼容 OpenAI /rerank 端点。',
     'http://localhost:8001',
     'PLAINTEXT:',
     'bge-reranker-v2-m3',
     0.00, 0, 10,
     true, true, NULL,
     NOW(), NOW(), NULL,
     'RERANKER')
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 4. 推进自增序列到当前最大 id，防止后续 INSERT 主键冲突
-- ---------------------------------------------------------------------------
SELECT setval(
    pg_get_serial_sequence('cs_auth.ai_model_config', 'id'),
    (SELECT MAX(id) FROM cs_auth.ai_model_config),
    true
);

COMMIT;
