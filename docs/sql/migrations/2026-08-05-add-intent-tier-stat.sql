-- 2026-08-05 P0-A DIT 三层意图分类明细表
-- 用途：作为管理台命中率/延迟报表的历史数据源（Micrometer Counter 进程重启即清零，无法承担历史趋势）
-- 适用：已存在的数据库环境（新环境由 conversation-service-schema.sql 全量初始化）
-- 执行方式：psql -U postgres -d aria_cs -f 本文件
CREATE TABLE IF NOT EXISTS cs_conversation.cs_intent_tier_stat
(
    id               BIGSERIAL PRIMARY KEY,
    domain_code      VARCHAR(64),
    reached_tier     VARCHAR(20)  NOT NULL,                 -- 最终到达层 RULE/EMBEDDING/LLM
    tier1_hit        BOOLEAN      NOT NULL DEFAULT FALSE,
    tier2_executed   BOOLEAN      NOT NULL DEFAULT FALSE,
    tier2_hit        BOOLEAN      NOT NULL DEFAULT FALSE,
    tier3_executed   BOOLEAN      NOT NULL DEFAULT FALSE,
    tier3_hit        BOOLEAN      NOT NULL DEFAULT FALSE,
    tier1_latency_ms INTEGER,
    tier2_latency_ms INTEGER,
    tier3_latency_ms INTEGER,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_intent_tier_stat_time
    ON cs_conversation.cs_intent_tier_stat (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_intent_tier_stat_domain
    ON cs_conversation.cs_intent_tier_stat (domain_code, created_at DESC);

COMMENT ON TABLE cs_conversation.cs_intent_tier_stat
    IS 'DIT 三层意图分类单次明细，供 Admin 命中率/延迟报表聚合';
