-- Migration: add keywords/patterns columns to cs_intent and cs_domain
-- Run this against an existing database to apply the schema changes.
-- Safe to re-run: all statements use IF NOT EXISTS / ON CONFLICT DO NOTHING.

-- 意图表新增关键词/正则列
ALTER TABLE cs_conversation.cs_intent
    ADD COLUMN IF NOT EXISTS keywords jsonb DEFAULT '[]'::jsonb NOT NULL,
    ADD COLUMN IF NOT EXISTS patterns jsonb DEFAULT '[]'::jsonb NOT NULL;

COMMENT ON COLUMN cs_conversation.cs_intent.keywords IS '关键词列表，JSON 字符串数组，大小写不敏感全文包含匹配，如 ["转人工","找真人"]';
COMMENT ON COLUMN cs_conversation.cs_intent.patterns IS '正则表达式列表，Java Pattern 语法，DOTALL|CASE_INSENSITIVE，如 ["^我要.*转.*人工"]';

-- 域路由表新增关键词/正则列
ALTER TABLE cs_conversation.cs_domain
    ADD COLUMN IF NOT EXISTS keywords jsonb DEFAULT '[]'::jsonb NOT NULL,
    ADD COLUMN IF NOT EXISTS patterns jsonb DEFAULT '[]'::jsonb NOT NULL;

COMMENT ON COLUMN cs_conversation.cs_domain.keywords IS '域路由关键词列表，命中则直接路由到该域，跳过 LLM';
COMMENT ON COLUMN cs_conversation.cs_domain.patterns IS '域路由正则列表，命中则直接路由到该域，跳过 LLM';

-- 路由配置种子数据（system_config 表）
INSERT INTO cs_auth.system_config (config_key, config_value, config_type, description, is_enabled)
VALUES (
  'routing.config',
  '{
    "intent": {
      "embeddingEnabled": false,
      "embeddingThreshold": 0.75,
      "minLlmConfidence": 0.0,
      "maxExamplesToInject": 5
    },
    "domain": {
      "ruleEnabled": true
    }
  }',
  'CUSTOMER_SERVICE',
  '意图路由级联配置（JSON）',
  true
) ON CONFLICT DO NOTHING;
