-- 新增客服接待配置：每个客服最大同时接待会话数
-- 默认值 5，可在管理后台通过 system_config 管理页面修改
INSERT INTO cs_auth.system_config (config_key, config_value, config_type, description, is_enabled, created_at, updated_at)
VALUES (
    'cs.agent.config',
    '{"maxSessionsPerAgent": 5}',
    'CUSTOMER_SERVICE',
    '客服接待配置：maxSessionsPerAgent 为每个客服最大同时接待会话数，达到阈值后不再自动分配，客服可主动超额接入',
    true,
    NOW(),
    NOW()
);
