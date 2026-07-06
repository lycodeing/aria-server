-- ============================================================
-- DIT 天气查询领域数据录入
-- migration-004-weather-dit.sql
-- 依赖：migration-002-dit-tables.sql 已执行
-- ============================================================

-- ============================================================
-- 1. 注册工具 (3个)
-- ============================================================

-- 工具1: 城市名候选发现工具（Open-Meteo Geocoding，无需 Key）
INSERT INTO cs_conversation.cs_tool (
    code, name, description,
    tool_type, http_method, url_template,
    headers_template, body_template, param_schema,
    response_jsonpath, auth_type, auth_config,
    timeout_ms, is_discover_tool, enabled
) VALUES (
    'geocoding_search',
    '城市名搜索',
    '根据城市名关键词搜索匹配的城市列表，返回城市名称、经纬度、国家等信息，用于槽位 DISCOVER 级候选发现',
    'HTTP', 'GET',
    'https://geocoding-api.open-meteo.com/v1/search?name={city_name}&count=5&language=zh&format=json',
    '{}', NULL,
    '{
        "city_name": {
            "type": "string",
            "required": true,
            "description": "城市名称关键词，如北京、上海、纽约"
        }
    }',
    '$.results[*].name',
    'NONE', '{}',
    5000, true, true
) ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    url_template = EXCLUDED.url_template,
    param_schema = EXCLUDED.param_schema,
    response_jsonpath = EXCLUDED.response_jsonpath,
    is_discover_tool = EXCLUDED.is_discover_tool,
    updated_at = NOW();

-- 工具2: 当前天气查询（wttr.in JSON API，无需 Key，支持中文城市名）
INSERT INTO cs_conversation.cs_tool (
    code, name, description,
    tool_type, http_method, url_template,
    headers_template, body_template, param_schema,
    response_jsonpath, auth_type, auth_config,
    timeout_ms, is_discover_tool, enabled
) VALUES (
    'get_current_weather',
    '查询当前天气',
    '查询指定城市的实时天气，包含温度、体感温度、湿度、风速、风向、天气状况等信息。使用 wttr.in 开源免费 API，支持中英文城市名。',
    'HTTP', 'GET',
    'https://wttr.in/{city}?format=j1',
    '{"Accept": "application/json"}', NULL,
    '{
        "city": {
            "type": "string",
            "required": true,
            "description": "城市名称，支持中文（如：北京）或英文（如：Beijing）"
        }
    }',
    '$.current_condition[0]',
    'NONE', '{}',
    8000, false, true
) ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    url_template = EXCLUDED.url_template,
    param_schema = EXCLUDED.param_schema,
    response_jsonpath = EXCLUDED.response_jsonpath,
    updated_at = NOW();

-- 工具3: 天气预报查询（wttr.in，支持3天预报）
INSERT INTO cs_conversation.cs_tool (
    code, name, description,
    tool_type, http_method, url_template,
    headers_template, body_template, param_schema,
    response_jsonpath, auth_type, auth_config,
    timeout_ms, is_discover_tool, enabled
) VALUES (
    'get_weather_forecast',
    '查询天气预报',
    '查询指定城市未来3天的天气预报，包含每日最高/最低温度、降水概率、UV指数、日出日落时间等。使用 wttr.in 开源免费 API。',
    'HTTP', 'GET',
    'https://wttr.in/{city}?format=j1',
    '{"Accept": "application/json"}', NULL,
    '{
        "city": {
            "type": "string",
            "required": true,
            "description": "城市名称，支持中文或英文"
        },
        "days": {
            "type": "integer",
            "required": false,
            "description": "预报天数，1-3天，默认3天",
            "default": 3
        }
    }',
    '$.weather',
    'NONE', '{}',
    8000, false, true
) ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    url_template = EXCLUDED.url_template,
    param_schema = EXCLUDED.param_schema,
    response_jsonpath = EXCLUDED.response_jsonpath,
    updated_at = NOW();

-- 工具4: AQI 空气质量查询（Open-Meteo Air Quality，无需 Key）
INSERT INTO cs_conversation.cs_tool (
    code, name, description,
    tool_type, http_method, url_template,
    headers_template, body_template, param_schema,
    response_jsonpath, auth_type, auth_config,
    timeout_ms, is_discover_tool, enabled
) VALUES (
    'get_air_quality',
    '查询空气质量',
    '查询指定城市的实时空气质量指数（AQI），包含PM2.5、PM10、臭氧、一氧化碳等污染物浓度。使用 Open-Meteo Air Quality API，开源免费。需要先用 geocoding_search 获取城市经纬度。',
    'HTTP', 'GET',
    'https://air-quality-api.open-meteo.com/v1/air-quality?latitude={latitude}&longitude={longitude}&current=pm2_5,pm10,european_aqi,us_aqi,carbon_monoxide,ozone',
    '{}', NULL,
    '{
        "latitude": {
            "type": "number",
            "required": true,
            "description": "城市纬度（WGS84），如北京为 39.9042"
        },
        "longitude": {
            "type": "number",
            "required": true,
            "description": "城市经度（WGS84），如北京为 116.4074"
        }
    }',
    '$.current',
    'NONE', '{}',
    8000, false, true
) ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    url_template = EXCLUDED.url_template,
    param_schema = EXCLUDED.param_schema,
    response_jsonpath = EXCLUDED.response_jsonpath,
    updated_at = NOW();

-- ============================================================
-- 2. 新增领域：天气助手
-- ============================================================
INSERT INTO cs_conversation.cs_domain (
    code, name, description,
    system_prompt_addon,
    knowledge_base_id, enabled
) VALUES (
    'weather',
    '天气助手',
    '天气查询智能客服，支持实时天气、多日预报、空气质量查询，基于开源免费 API',
    '你是一个专业的天气查询助手。当用户询问天气时，请调用相应工具获取真实数据后回复，不要编造天气信息。回复时请使用简洁友好的语言，可以适当加上天气相关的贴心提示（如出行建议、穿衣提醒）。',
    NULL, true
) ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    system_prompt_addon = EXCLUDED.system_prompt_addon,
    updated_at = NOW();

-- ============================================================
-- 3. 录入意图（5个）
-- ============================================================

-- 意图1: 查询当前天气
INSERT INTO cs_conversation.cs_intent (
    domain_id, code, name, description,
    example_queries,
    auto_transfer, skip_rag, fallback_reply,
    enabled, sort_order
) VALUES (
    (SELECT id FROM cs_conversation.cs_domain WHERE code = 'weather'),
    'query_current_weather',
    '查询当前天气',
    '用户询问某城市当前天气状况，包含温度、湿度、风速、天气描述等实时信息',
    '["今天北京天气怎么样", "上海现在多少度", "广州天气", "深圳今天热不热", "现在武汉天气如何", "帮我查一下成都的天气"]',
    false, true,
    '抱歉，暂时无法获取天气信息，请稍后重试或访问天气应用查询。',
    true, 1
) ON CONFLICT (domain_id, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    example_queries = EXCLUDED.example_queries,
    fallback_reply = EXCLUDED.fallback_reply,
    sort_order = EXCLUDED.sort_order;

-- 意图2: 查询天气预报
INSERT INTO cs_conversation.cs_intent (
    domain_id, code, name, description,
    example_queries,
    auto_transfer, skip_rag, fallback_reply,
    enabled, sort_order
) VALUES (
    (SELECT id FROM cs_conversation.cs_domain WHERE code = 'weather'),
    'query_weather_forecast',
    '查询天气预报',
    '用户询问某城市未来几天的天气预报，包含每日天气、温度区间、降水概率等',
    '["明天上海天气", "北京未来三天天气", "这周广州会下雨吗", "杭州周末天气怎么样", "成都明后天天气预报", "深圳本周天气"]',
    false, true,
    '抱歉，暂时无法获取天气预报，请稍后重试。',
    true, 2
) ON CONFLICT (domain_id, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    example_queries = EXCLUDED.example_queries,
    fallback_reply = EXCLUDED.fallback_reply,
    sort_order = EXCLUDED.sort_order;

-- 意图3: 查询空气质量
INSERT INTO cs_conversation.cs_intent (
    domain_id, code, name, description,
    example_queries,
    auto_transfer, skip_rag, fallback_reply,
    enabled, sort_order
) VALUES (
    (SELECT id FROM cs_conversation.cs_domain WHERE code = 'weather'),
    'query_air_quality',
    '查询空气质量',
    '用户询问某城市的空气质量、AQI指数、PM2.5浓度、是否适合户外活动等',
    '["北京今天空气质量怎么样", "上海PM2.5多少", "今天适合出门跑步吗", "广州空气质量好吗", "深圳AQI是多少", "今天口罩要戴吗"]',
    false, true,
    '抱歉，暂时无法获取空气质量数据，请稍后重试。',
    true, 3
) ON CONFLICT (domain_id, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    example_queries = EXCLUDED.example_queries,
    fallback_reply = EXCLUDED.fallback_reply,
    sort_order = EXCLUDED.sort_order;

-- 意图4: 出行天气建议
INSERT INTO cs_conversation.cs_intent (
    domain_id, code, name, description,
    example_queries,
    auto_transfer, skip_rag, fallback_reply,
    enabled, sort_order
) VALUES (
    (SELECT id FROM cs_conversation.cs_domain WHERE code = 'weather'),
    'travel_weather_advice',
    '出行天气建议',
    '用户询问某城市是否适合出行、旅游，或者询问某段时间内某地的天气是否适合特定活动',
    '["下周去北京旅游天气好吗", "去三亚度假天气怎么样", "明天开车去上海路上天气咋样", "这周末适合去爬山吗", "去杭州西湖游玩天气合适吗"]',
    false, false,
    '抱歉，暂时无法获取出行天气建议，请查看天气应用或联系客服。',
    true, 4
) ON CONFLICT (domain_id, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    example_queries = EXCLUDED.example_queries,
    fallback_reply = EXCLUDED.fallback_reply,
    sort_order = EXCLUDED.sort_order;

-- 意图5: 超出范围（转人工）
INSERT INTO cs_conversation.cs_intent (
    domain_id, code, name, description,
    example_queries,
    auto_transfer, skip_rag, fallback_reply,
    enabled, sort_order
) VALUES (
    (SELECT id FROM cs_conversation.cs_domain WHERE code = 'weather'),
    'out_of_scope',
    '超出范围',
    '用户提问与天气无关，或需要人工处理的情况，自动转人工服务',
    '["帮我订机票", "我要投诉", "怎么退款", "人工客服", "找真人"]',
    true, true,
    '您的问题超出了天气助手的服务范围，正在为您转接人工客服...',
    true, 5
) ON CONFLICT (domain_id, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    example_queries = EXCLUDED.example_queries,
    auto_transfer = EXCLUDED.auto_transfer,
    fallback_reply = EXCLUDED.fallback_reply,
    sort_order = EXCLUDED.sort_order;

-- ============================================================
-- 4. 槽位配置
-- ============================================================

-- 意图1 槽位：city（当前天气）
INSERT INTO cs_conversation.cs_intent_slot (
    intent_id, slot_name, slot_type, description,
    required, resolve_strategy,
    session_key, discover_tool_code, discover_fixed_params,
    ask_user_prompt, enum_values, sort_order
) VALUES (
    (SELECT id FROM cs_conversation.cs_intent
     WHERE domain_id = (SELECT id FROM cs_conversation.cs_domain WHERE code = 'weather')
     AND code = 'query_current_weather'),
    'city', 'string',
    '需要查询天气的城市名称',
    true,
    '["EXTRACT", "SESSION", "DISCOVER", "ASK_USER"]',
    'last_city',
    'geocoding_search',
    '{}',
    '请问您想查询哪个城市的天气？例如：北京、上海、广州',
    NULL, 1
) ON CONFLICT (intent_id, slot_name) DO UPDATE SET
    description = EXCLUDED.description,
    resolve_strategy = EXCLUDED.resolve_strategy,
    session_key = EXCLUDED.session_key,
    discover_tool_code = EXCLUDED.discover_tool_code,
    ask_user_prompt = EXCLUDED.ask_user_prompt;

-- 意图2 槽位：city（天气预报）
INSERT INTO cs_conversation.cs_intent_slot (
    intent_id, slot_name, slot_type, description,
    required, resolve_strategy,
    session_key, discover_tool_code, discover_fixed_params,
    ask_user_prompt, enum_values, sort_order
) VALUES (
    (SELECT id FROM cs_conversation.cs_intent
     WHERE domain_id = (SELECT id FROM cs_conversation.cs_domain WHERE code = 'weather')
     AND code = 'query_weather_forecast'),
    'city', 'string',
    '需要查询天气预报的城市名称',
    true,
    '["EXTRACT", "SESSION", "DISCOVER", "ASK_USER"]',
    'last_city',
    'geocoding_search',
    '{}',
    '请问您想查询哪个城市的天气预报？',
    NULL, 1
) ON CONFLICT (intent_id, slot_name) DO UPDATE SET
    description = EXCLUDED.description,
    resolve_strategy = EXCLUDED.resolve_strategy,
    session_key = EXCLUDED.session_key,
    discover_tool_code = EXCLUDED.discover_tool_code,
    ask_user_prompt = EXCLUDED.ask_user_prompt;

-- 意图2 槽位：days（预报天数，可选）
INSERT INTO cs_conversation.cs_intent_slot (
    intent_id, slot_name, slot_type, description,
    required, resolve_strategy,
    session_key, discover_tool_code, discover_fixed_params,
    ask_user_prompt, enum_values, sort_order
) VALUES (
    (SELECT id FROM cs_conversation.cs_intent
     WHERE domain_id = (SELECT id FROM cs_conversation.cs_domain WHERE code = 'weather')
     AND code = 'query_weather_forecast'),
    'days', 'integer',
    '预报天数，1-3天',
    false,
    '["EXTRACT"]',
    NULL, NULL, '{}',
    NULL,
    '[1, 2, 3]', 2
) ON CONFLICT (intent_id, slot_name) DO UPDATE SET
    description = EXCLUDED.description,
    resolve_strategy = EXCLUDED.resolve_strategy,
    enum_values = EXCLUDED.enum_values;

-- 意图3 槽位：city（空气质量）
INSERT INTO cs_conversation.cs_intent_slot (
    intent_id, slot_name, slot_type, description,
    required, resolve_strategy,
    session_key, discover_tool_code, discover_fixed_params,
    ask_user_prompt, enum_values, sort_order
) VALUES (
    (SELECT id FROM cs_conversation.cs_intent
     WHERE domain_id = (SELECT id FROM cs_conversation.cs_domain WHERE code = 'weather')
     AND code = 'query_air_quality'),
    'city', 'string',
    '需要查询空气质量的城市名称',
    true,
    '["EXTRACT", "SESSION", "DISCOVER", "ASK_USER"]',
    'last_city',
    'geocoding_search',
    '{}',
    '请问您想查询哪个城市的空气质量？',
    NULL, 1
) ON CONFLICT (intent_id, slot_name) DO UPDATE SET
    description = EXCLUDED.description,
    resolve_strategy = EXCLUDED.resolve_strategy,
    session_key = EXCLUDED.session_key,
    discover_tool_code = EXCLUDED.discover_tool_code,
    ask_user_prompt = EXCLUDED.ask_user_prompt;

-- 意图4 槽位：city（出行建议）
INSERT INTO cs_conversation.cs_intent_slot (
    intent_id, slot_name, slot_type, description,
    required, resolve_strategy,
    session_key, discover_tool_code, discover_fixed_params,
    ask_user_prompt, enum_values, sort_order
) VALUES (
    (SELECT id FROM cs_conversation.cs_intent
     WHERE domain_id = (SELECT id FROM cs_conversation.cs_domain WHERE code = 'weather')
     AND code = 'travel_weather_advice'),
    'city', 'string',
    '目的地城市名称',
    true,
    '["EXTRACT", "SESSION", "DISCOVER", "ASK_USER"]',
    'last_city',
    'geocoding_search',
    '{}',
    '请问您打算去哪个城市？我来帮您查询出行天气。',
    NULL, 1
) ON CONFLICT (intent_id, slot_name) DO UPDATE SET
    description = EXCLUDED.description,
    resolve_strategy = EXCLUDED.resolve_strategy,
    session_key = EXCLUDED.session_key,
    discover_tool_code = EXCLUDED.discover_tool_code,
    ask_user_prompt = EXCLUDED.ask_user_prompt;

-- ============================================================
-- 5. 意图-工具绑定
-- ============================================================

-- 意图1 绑定 get_current_weather（REQUIRED，立即执行）
INSERT INTO cs_conversation.cs_intent_tool (
    intent_id, tool_id, execution_mode, execution_order, param_mappings
) VALUES (
    (SELECT id FROM cs_conversation.cs_intent
     WHERE domain_id = (SELECT id FROM cs_conversation.cs_domain WHERE code = 'weather')
     AND code = 'query_current_weather'),
    (SELECT id FROM cs_conversation.cs_tool WHERE code = 'get_current_weather'),
    'REQUIRED', 1,
    '{"city": {"source": "slot", "key": "city"}}'
) ON CONFLICT (intent_id, tool_id) DO UPDATE SET
    execution_mode = EXCLUDED.execution_mode,
    param_mappings = EXCLUDED.param_mappings;

-- 意图2 绑定 get_weather_forecast（REQUIRED）
INSERT INTO cs_conversation.cs_intent_tool (
    intent_id, tool_id, execution_mode, execution_order, param_mappings
) VALUES (
    (SELECT id FROM cs_conversation.cs_intent
     WHERE domain_id = (SELECT id FROM cs_conversation.cs_domain WHERE code = 'weather')
     AND code = 'query_weather_forecast'),
    (SELECT id FROM cs_conversation.cs_tool WHERE code = 'get_weather_forecast'),
    'REQUIRED', 1,
    '{"city": {"source": "slot", "key": "city"}, "days": {"source": "slot", "key": "days"}}'
) ON CONFLICT (intent_id, tool_id) DO UPDATE SET
    execution_mode = EXCLUDED.execution_mode,
    param_mappings = EXCLUDED.param_mappings;

-- 意图3 绑定 get_air_quality（REQUIRED）
-- 注：AQI API 需经纬度，先用 geocoding_search 发现，再调 get_air_quality
INSERT INTO cs_conversation.cs_intent_tool (
    intent_id, tool_id, execution_mode, execution_order, param_mappings
) VALUES (
    (SELECT id FROM cs_conversation.cs_intent
     WHERE domain_id = (SELECT id FROM cs_conversation.cs_domain WHERE code = 'weather')
     AND code = 'query_air_quality'),
    (SELECT id FROM cs_conversation.cs_tool WHERE code = 'get_air_quality'),
    'OPTIONAL', 1,
    '{"latitude": {"source": "tool_result", "key": "geocoding.latitude"}, "longitude": {"source": "tool_result", "key": "geocoding.longitude"}}'
) ON CONFLICT (intent_id, tool_id) DO UPDATE SET
    execution_mode = EXCLUDED.execution_mode,
    param_mappings = EXCLUDED.param_mappings;

-- 意图4 绑定 get_weather_forecast（出行建议，REQUIRED）
INSERT INTO cs_conversation.cs_intent_tool (
    intent_id, tool_id, execution_mode, execution_order, param_mappings
) VALUES (
    (SELECT id FROM cs_conversation.cs_intent
     WHERE domain_id = (SELECT id FROM cs_conversation.cs_domain WHERE code = 'weather')
     AND code = 'travel_weather_advice'),
    (SELECT id FROM cs_conversation.cs_tool WHERE code = 'get_weather_forecast'),
    'REQUIRED', 1,
    '{"city": {"source": "slot", "key": "city"}}'
) ON CONFLICT (intent_id, tool_id) DO UPDATE SET
    execution_mode = EXCLUDED.execution_mode,
    param_mappings = EXCLUDED.param_mappings;

-- ============================================================
-- 验证
-- ============================================================
DO $$
DECLARE
    v_domain_id  BIGINT;
    v_tool_cnt   INT;
    v_intent_cnt INT;
    v_slot_cnt   INT;
    v_bind_cnt   INT;
BEGIN
    SELECT id INTO v_domain_id FROM cs_conversation.cs_domain WHERE code = 'weather';
    SELECT COUNT(*) INTO v_tool_cnt   FROM cs_conversation.cs_tool
        WHERE code IN ('geocoding_search','get_current_weather','get_weather_forecast','get_air_quality');
    SELECT COUNT(*) INTO v_intent_cnt FROM cs_conversation.cs_intent WHERE domain_id = v_domain_id;
    SELECT COUNT(*) INTO v_slot_cnt   FROM cs_conversation.cs_intent_slot
        WHERE intent_id IN (SELECT id FROM cs_conversation.cs_intent WHERE domain_id = v_domain_id);
    SELECT COUNT(*) INTO v_bind_cnt   FROM cs_conversation.cs_intent_tool
        WHERE intent_id IN (SELECT id FROM cs_conversation.cs_intent WHERE domain_id = v_domain_id);

    RAISE NOTICE '✅ 天气领域数据录入完成';
    RAISE NOTICE '   领域 weather id = %', v_domain_id;
    RAISE NOTICE '   工具数量: % / 4', v_tool_cnt;
    RAISE NOTICE '   意图数量: % / 5', v_intent_cnt;
    RAISE NOTICE '   槽位数量: % / 5', v_slot_cnt;
    RAISE NOTICE '   工具绑定: % / 4', v_bind_cnt;
END $$;
