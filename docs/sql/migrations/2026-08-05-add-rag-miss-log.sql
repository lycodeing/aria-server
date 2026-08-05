-- 2026-08-05 P0-B RAG 检索质量记录：新增 cs_rag_miss_log 表
-- 适用：已存在的数据库环境（新环境由 conversation-service-schema.sql 全量初始化）
-- 执行方式：psql -U postgres -d aria_cs -f 本文件
CREATE TABLE IF NOT EXISTS cs_conversation.cs_rag_miss_log
(
    id            BIGSERIAL   PRIMARY KEY,
    session_id    VARCHAR(64) NOT NULL,
    query_text    TEXT        NOT NULL,
    top1_score    DOUBLE PRECISION,                       -- 最高分 chunk 的 score；NULL=完全未命中（0 结果）
    hit_count     INTEGER     NOT NULL DEFAULT 0,         -- 本次检索命中 chunk 数
    is_miss       BOOLEAN     NOT NULL DEFAULT FALSE,     -- TRUE=hit_count=0 或 top1_score 低于阈值
    source        VARCHAR(20),                            -- top1 命中来源 VECTOR/FULL_TEXT/RERANK；空结果为 NULL
    domain_code   VARCHAR(64),                            -- 当前域 code，可为 NULL
    intent_codes  TEXT,                                   -- 本次识别到的意图 code，逗号分隔，可为 NULL
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_rag_miss_log_session ON cs_conversation.cs_rag_miss_log (session_id);
CREATE INDEX IF NOT EXISTS idx_rag_miss_log_miss    ON cs_conversation.cs_rag_miss_log (is_miss, created_at DESC);

COMMENT ON TABLE cs_conversation.cs_rag_miss_log
    IS 'RAG 检索质量快照：每次 FAQ 链路检索后异步写一行，驱动知识库覆盖度分析';
