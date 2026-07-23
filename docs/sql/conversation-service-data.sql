-- conversation-service (cs_conversation data)
--
-- PostgreSQL database dump
--

-- Dumped from database version 16.14 (Debian 16.14-1.pgdg12+1)
-- Dumped by pg_dump version 16.14 (Debian 16.14-1.pgdg12+1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;



--
-- Data for Name: cs_domain; Type: TABLE DATA; Schema: cs_conversation; Owner: -
--

INSERT INTO cs_conversation.cs_domain (id, code, name, description, system_prompt_addon, knowledge_base_id, enabled, created_at, updated_at) VALUES (3, 'ecommerce', '电商客服', '电商平台客服，处理订单、退款、商品咨询等', '你是一名专业的电商平台客服助手，熟悉订单、退款、物流等业务流程。回答要简洁准确。', NULL, true, '2026-07-05 00:13:54.520015', '2026-07-05 00:13:54.520015');
INSERT INTO cs_conversation.cs_domain (id, code, name, description, system_prompt_addon, knowledge_base_id, enabled, created_at, updated_at) VALUES (4, 'finance', '金融客服', '银行及金融产品客服，处理账户查询、理财咨询等', '你是一名专业的金融客服助手。注意：涉及转账、取款等敏感操作必须转接人工，保护用户资金安全。', NULL, true, '2026-07-05 00:13:54.658105', '2026-07-05 00:13:54.658105');
INSERT INTO cs_conversation.cs_domain (id, code, name, description, system_prompt_addon, knowledge_base_id, enabled, created_at, updated_at) VALUES (5, 'travel', '酒旅客服', '酒店预订和旅游服务客服', '你是一名专业的酒旅客服助手，擅长酒店推荐、预订流程和旅游攻略。', NULL, true, '2026-07-05 00:13:54.721371', '2026-07-05 00:13:54.721371');
INSERT INTO cs_conversation.cs_domain (id, code, name, description, system_prompt_addon, knowledge_base_id, enabled, created_at, updated_at) VALUES (6, 'weather', '天气助手', '天气查询智能客服，支持实时天气、多日预报、空气质量查询，基于开源免费 API', '你是一个专业的天气查询助手。当用户询问天气时，请调用相应工具获取真实数据后回复，不要编造天气信息。回复时请使用简洁友好的语言，可以适当加上天气相关的贴心提示（如出行建议、穿衣提醒）。', NULL, true, '2026-07-04 16:32:05.98089', '2026-07-04 16:32:05.98089');

--
-- Data for Name: cs_intent; Type: TABLE DATA; Schema: cs_conversation; Owner: -
--

INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (1, 3, 'query_order', '查询订单', '用户想查询订单状态、物流信息或订单详情', '["帮我查订单", "我的包裹到哪了", "查一下单号ORD001", "订单什么时候发货"]', false, true, NULL, true, 10);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (2, 3, 'apply_refund', '申请退款', '用户想申请退款或退货', '["我要退款", "申请退货", "这个商品质量太差要退", "退款流程是什么"]', false, true, NULL, true, 20);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (3, 3, 'product_inquiry', '商品咨询', '用户咨询商品详情、规格、库存、适用场景等', '["这款商品有什么颜色", "尺码怎么选", "适合多大年龄", "材质是什么"]', false, false, NULL, true, 30);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (4, 3, 'complaint', '投诉', '用户对服务或商品表达强烈不满，需要投诉', '["我要投诉", "服务太差了", "要求赔偿", "找你们负责人"]', true, false, '非常抱歉给您带来不好的体验，已为您转接专属客服处理投诉。', true, 40);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (5, 3, 'chitchat', '闲聊', '用户进行日常闲聊、问候，与业务无关', '["你好", "今天天气怎么样", "你是谁", "在吗"]', false, true, NULL, true, 50);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (6, 4, 'query_balance', '查询账户余额', '用户想查询银行卡或账户的当前余额', '["我的余额是多少", "查一下账户", "卡里还有多少钱", "账户余额查询"]', false, true, NULL, true, 10);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (7, 4, 'transfer_money', '转账汇款', '用户想进行转账或汇款操作', '["我要转账", "帮我汇款", "转钱给别人", "网银转账"]', true, false, '转账操作涉及资金安全，已为您转接专属人工客服核实身份后处理。', true, 20);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (8, 4, 'investment_inquiry', '理财咨询', '用户咨询理财产品、基金、利率等投资相关问题', '["有什么理财产品", "基金怎么买", "存款利率是多少", "推荐一些低风险产品"]', false, false, NULL, true, 30);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (9, 4, 'report_loss', '挂失补办', '用户需要挂失银行卡或补办卡片', '["银行卡丢了", "卡被盗了要挂失", "怎么补办银行卡", "申请挂失"]', true, false, '挂失业务需要身份核实，已为您转接人工客服处理。', true, 40);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (10, 5, 'search_hotel', '搜索酒店', '用户想查找某城市的可用酒店', '["帮我找北京的酒店", "上海有什么好酒店", "三亚五星级酒店推荐", "明天去杭州住哪好"]', false, true, NULL, true, 10);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (11, 5, 'make_booking', '预订房间', '用户想预订特定酒店的房间', '["我要预订", "帮我订一间", "确认预订", "怎么下单"]', true, false, '预订操作需要确认详细信息，已为您转接人工客服协助完成预订。', true, 20);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (12, 5, 'travel_guide', '旅游攻略', '用户想了解景点推荐、旅游路线、当地特色', '["三亚有什么好玩的", "推荐一下北京景点", "云南旅游攻略", "西藏几月份去好"]', false, false, NULL, true, 30);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (13, 6, 'query_current_weather', '查询当前天气', '用户询问某城市当前天气状况，包含温度、湿度、风速、天气描述等实时信息', '["今天北京天气怎么样", "上海现在多少度", "广州天气", "深圳今天热不热", "现在武汉天气如何", "帮我查一下成都的天气"]', false, true, '抱歉，暂时无法获取天气信息，请稍后重试或访问天气应用查询。', true, 1);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (14, 6, 'query_weather_forecast', '查询天气预报', '用户询问某城市未来几天的天气预报，包含每日天气、温度区间、降水概率等', '["明天上海天气", "北京未来三天天气", "这周广州会下雨吗", "杭州周末天气怎么样", "成都明后天天气预报", "深圳本周天气"]', false, true, '抱歉，暂时无法获取天气预报，请稍后重试。', true, 2);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (15, 6, 'query_air_quality', '查询空气质量', '用户询问某城市的空气质量、AQI指数、PM2.5浓度、是否适合户外活动等', '["北京今天空气质量怎么样", "上海PM2.5多少", "今天适合出门跑步吗", "广州空气质量好吗", "深圳AQI是多少", "今天口罩要戴吗"]', false, true, '抱歉，暂时无法获取空气质量数据，请稍后重试。', true, 3);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (16, 6, 'travel_weather_advice', '出行天气建议', '用户询问某城市是否适合出行、旅游，或者询问某段时间内某地的天气是否适合特定活动', '["下周去北京旅游天气好吗", "去三亚度假天气怎么样", "明天开车去上海路上天气咋样", "这周末适合去爬山吗", "去杭州西湖游玩天气合适吗"]', false, false, '抱歉，暂时无法获取出行天气建议，请查看天气应用或联系客服。', true, 4);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (17, 6, 'out_of_scope', '超出范围', '用户提问与天气无关，或需要人工处理的情况，自动转人工服务', '["帮我订机票", "我要投诉", "怎么退款", "人工客服", "找真人"]', true, true, '您的问题超出了天气助手的服务范围，正在为您转接人工客服...', true, 5);

--
-- Data for Name: cs_intent_slot; Type: TABLE DATA; Schema: cs_conversation; Owner: -
--

INSERT INTO cs_conversation.cs_intent_slot (id, intent_id, slot_name, slot_type, description, required, resolve_strategy, session_key, discover_tool_code, discover_fixed_params, ask_user_prompt, enum_values, sort_order) VALUES (1, 1, 'order_id', 'string', '订单号，格式为ORD开头的字符串', true, '["EXTRACT", "SESSION", "DISCOVER", "ASK_USER"]', NULL, 'list_orders', '{}', '请提供您要查询的订单号，可在购买确认短信中找到', NULL, 0);
INSERT INTO cs_conversation.cs_intent_slot (id, intent_id, slot_name, slot_type, description, required, resolve_strategy, session_key, discover_tool_code, discover_fixed_params, ask_user_prompt, enum_values, sort_order) VALUES (2, 2, 'order_id', 'string', '需要退款的订单号', true, '["EXTRACT", "SESSION", "DISCOVER", "ASK_USER"]', NULL, 'list_orders', '{}', '请提供需要退款的订单号', NULL, 0);
INSERT INTO cs_conversation.cs_intent_slot (id, intent_id, slot_name, slot_type, description, required, resolve_strategy, session_key, discover_tool_code, discover_fixed_params, ask_user_prompt, enum_values, sort_order) VALUES (3, 6, 'account_id', 'string', '账户ID或银行卡号', true, '["SESSION"]', 'account_id', NULL, '{}', '请提供您的账户ID', NULL, 0);
INSERT INTO cs_conversation.cs_intent_slot (id, intent_id, slot_name, slot_type, description, required, resolve_strategy, session_key, discover_tool_code, discover_fixed_params, ask_user_prompt, enum_values, sort_order) VALUES (4, 10, 'city', 'string', '目标城市名称', true, '["EXTRACT", "ASK_USER"]', NULL, NULL, '{}', '请问您要去哪个城市？', NULL, 0);
INSERT INTO cs_conversation.cs_intent_slot (id, intent_id, slot_name, slot_type, description, required, resolve_strategy, session_key, discover_tool_code, discover_fixed_params, ask_user_prompt, enum_values, sort_order) VALUES (5, 10, 'check_in', 'date', '入住日期，格式 YYYY-MM-DD', true, '["EXTRACT", "ASK_USER"]', NULL, NULL, '{}', '请问您计划哪天入住？', NULL, 1);
INSERT INTO cs_conversation.cs_intent_slot (id, intent_id, slot_name, slot_type, description, required, resolve_strategy, session_key, discover_tool_code, discover_fixed_params, ask_user_prompt, enum_values, sort_order) VALUES (6, 10, 'check_out', 'date', '退房日期，格式 YYYY-MM-DD', true, '["EXTRACT", "ASK_USER"]', NULL, NULL, '{}', '请问哪天退房？', NULL, 2);
INSERT INTO cs_conversation.cs_intent_slot (id, intent_id, slot_name, slot_type, description, required, resolve_strategy, session_key, discover_tool_code, discover_fixed_params, ask_user_prompt, enum_values, sort_order) VALUES (7, 13, 'city', 'string', '需要查询天气的城市名称', true, '["EXTRACT", "SESSION", "DISCOVER", "ASK_USER"]', 'last_city', 'geocoding_search', '{}', '请问您想查询哪个城市的天气？例如：北京、上海、广州', NULL, 1);
INSERT INTO cs_conversation.cs_intent_slot (id, intent_id, slot_name, slot_type, description, required, resolve_strategy, session_key, discover_tool_code, discover_fixed_params, ask_user_prompt, enum_values, sort_order) VALUES (8, 14, 'city', 'string', '需要查询天气预报的城市名称', true, '["EXTRACT", "SESSION", "DISCOVER", "ASK_USER"]', 'last_city', 'geocoding_search', '{}', '请问您想查询哪个城市的天气预报？', NULL, 1);
INSERT INTO cs_conversation.cs_intent_slot (id, intent_id, slot_name, slot_type, description, required, resolve_strategy, session_key, discover_tool_code, discover_fixed_params, ask_user_prompt, enum_values, sort_order) VALUES (9, 14, 'days', 'integer', '预报天数，1-3天', false, '["EXTRACT"]', NULL, NULL, '{}', NULL, '[1, 2, 3]', 2);
INSERT INTO cs_conversation.cs_intent_slot (id, intent_id, slot_name, slot_type, description, required, resolve_strategy, session_key, discover_tool_code, discover_fixed_params, ask_user_prompt, enum_values, sort_order) VALUES (10, 15, 'city', 'string', '需要查询空气质量的城市名称', true, '["EXTRACT", "SESSION", "DISCOVER", "ASK_USER"]', 'last_city', 'geocoding_search', '{}', '请问您想查询哪个城市的空气质量？', NULL, 1);
INSERT INTO cs_conversation.cs_intent_slot (id, intent_id, slot_name, slot_type, description, required, resolve_strategy, session_key, discover_tool_code, discover_fixed_params, ask_user_prompt, enum_values, sort_order) VALUES (11, 16, 'city', 'string', '目的地城市名称', true, '["EXTRACT", "SESSION", "DISCOVER", "ASK_USER"]', 'last_city', 'geocoding_search', '{}', '请问您打算去哪个城市？我来帮您查询出行天气。', NULL, 1);

--
-- Data for Name: cs_tool; Type: TABLE DATA; Schema: cs_conversation; Owner: -
--

INSERT INTO cs_conversation.cs_tool (id, code, name, description, tool_type, http_method, url_template, headers_template, body_template, param_schema, response_jsonpath, auth_type, auth_config, timeout_ms, is_discover_tool, enabled, created_at, updated_at) VALUES (3, 'list_orders', '查询订单列表', '查询用户的订单列表，支持按状态过滤。isDiscoverTool=true，可作为槽位DISCOVER级发现工具', 'HTTP', 'GET', 'https://api.example.com/orders', '{}', NULL, '{"status": {"type": "string", "description": "订单状态（unpaid/shipped/completed）"}, "user_id": {"type": "string", "description": "用户ID"}}', '$.data.orders', 'NONE', '{}', 5000, true, true, '2026-07-05 00:13:54.474449', '2026-07-05 00:13:54.474449');
INSERT INTO cs_conversation.cs_tool (id, code, name, description, tool_type, http_method, url_template, headers_template, body_template, param_schema, response_jsonpath, auth_type, auth_config, timeout_ms, is_discover_tool, enabled, created_at, updated_at) VALUES (4, 'get_order', '查询订单详情', '根据订单号获取订单详情，包含商品信息、金额、物流状态', 'HTTP', 'GET', 'https://api.example.com/orders/{order_id}', '{}', NULL, '{"order_id": {"type": "string", "required": true, "description": "订单号"}}', '$.data', 'NONE', '{}', 5000, false, true, '2026-07-05 00:13:54.487366', '2026-07-05 00:13:54.487366');
INSERT INTO cs_conversation.cs_tool (id, code, name, description, tool_type, http_method, url_template, headers_template, body_template, param_schema, response_jsonpath, auth_type, auth_config, timeout_ms, is_discover_tool, enabled, created_at, updated_at) VALUES (5, 'create_refund', '创建退款申请', '为指定订单创建退款申请，需要订单号，退款原因可选。LLM决定是否调用', 'HTTP', 'POST', 'https://api.example.com/refunds', '{}', '{"reason": "{reason}", "order_id": "{order_id}"}', '{"reason": {"type": "string", "description": "退款原因"}, "order_id": {"type": "string", "required": true, "description": "订单号"}}', '$.data', 'NONE', '{}', 8000, false, true, '2026-07-05 00:13:54.495835', '2026-07-05 00:13:54.495835');
INSERT INTO cs_conversation.cs_tool (id, code, name, description, tool_type, http_method, url_template, headers_template, body_template, param_schema, response_jsonpath, auth_type, auth_config, timeout_ms, is_discover_tool, enabled, created_at, updated_at) VALUES (6, 'get_balance', '查询账户余额', '查询指定账户的当前余额和可用额度', 'HTTP', 'GET', 'https://api.example.com/accounts/{account_id}/balance', '{}', NULL, '{"account_id": {"type": "string", "required": true, "description": "账户ID"}}', '$.data', 'NONE', '{}', 5000, false, true, '2026-07-05 00:13:54.504385', '2026-07-05 00:13:54.504385');
INSERT INTO cs_conversation.cs_tool (id, code, name, description, tool_type, http_method, url_template, headers_template, body_template, param_schema, response_jsonpath, auth_type, auth_config, timeout_ms, is_discover_tool, enabled, created_at, updated_at) VALUES (7, 'search_hotel', '搜索酒店', '根据城市和入离店日期搜索可用酒店列表，返回房型和价格', 'HTTP', 'POST', 'https://api.example.com/hotels/search', '{}', '{"city": "{city}", "check_in": "{check_in}", "check_out": "{check_out}"}', '{"city": {"type": "string", "required": true, "description": "城市名称"}, "check_in": {"type": "string", "required": true, "description": "入住日期 YYYY-MM-DD"}, "check_out": {"type": "string", "required": true, "description": "退房日期 YYYY-MM-DD"}}', '$.data.hotels', 'NONE', '{}', 8000, false, true, '2026-07-05 00:13:54.511747', '2026-07-05 00:13:54.511747');
INSERT INTO cs_conversation.cs_tool (id, code, name, description, tool_type, http_method, url_template, headers_template, body_template, param_schema, response_jsonpath, auth_type, auth_config, timeout_ms, is_discover_tool, enabled, created_at, updated_at) VALUES (8, 'geocoding_search', '城市名搜索', '根据城市名关键词搜索匹配的城市列表，返回城市名称、经纬度、国家等信息，用于槽位 DISCOVER 级候选发现', 'HTTP', 'GET', 'https://geocoding-api.open-meteo.com/v1/search?name={city_name}&count=5&language=zh&format=json', '{}', NULL, '{"city_name": {"type": "string", "required": true, "description": "城市名称关键词，如北京、上海、纽约"}}', '$.results[*].name', 'NONE', '{}', 5000, true, true, '2026-07-04 16:32:05.961021', '2026-07-04 16:32:05.961021');
INSERT INTO cs_conversation.cs_tool (id, code, name, description, tool_type, http_method, url_template, headers_template, body_template, param_schema, response_jsonpath, auth_type, auth_config, timeout_ms, is_discover_tool, enabled, created_at, updated_at) VALUES (9, 'get_current_weather', '查询当前天气', '查询指定城市的实时天气，包含温度、体感温度、湿度、风速、风向、天气状况等信息。使用 wttr.in 开源免费 API，支持中英文城市名。', 'HTTP', 'GET', 'https://wttr.in/{city}?format=j1', '{"Accept": "application/json"}', NULL, '{"city": {"type": "string", "required": true, "description": "城市名称，支持中文（如：北京）或英文（如：Beijing）"}}', '$.current_condition[0]', 'NONE', '{}', 8000, false, true, '2026-07-04 16:32:05.973958', '2026-07-04 16:32:05.973958');
INSERT INTO cs_conversation.cs_tool (id, code, name, description, tool_type, http_method, url_template, headers_template, body_template, param_schema, response_jsonpath, auth_type, auth_config, timeout_ms, is_discover_tool, enabled, created_at, updated_at) VALUES (10, 'get_weather_forecast', '查询天气预报', '查询指定城市未来3天的天气预报，包含每日最高/最低温度、降水概率、UV指数、日出日落时间等。使用 wttr.in 开源免费 API。', 'HTTP', 'GET', 'https://wttr.in/{city}?format=j1', '{"Accept": "application/json"}', NULL, '{"city": {"type": "string", "required": true, "description": "城市名称，支持中文或英文"}, "days": {"type": "integer", "default": 3, "required": false, "description": "预报天数，1-3天，默认3天"}}', '$.weather', 'NONE', '{}', 8000, false, true, '2026-07-04 16:32:05.977465', '2026-07-04 16:32:05.977465');
INSERT INTO cs_conversation.cs_tool (id, code, name, description, tool_type, http_method, url_template, headers_template, body_template, param_schema, response_jsonpath, auth_type, auth_config, timeout_ms, is_discover_tool, enabled, created_at, updated_at) VALUES (11, 'get_air_quality', '查询空气质量', '查询指定城市的实时空气质量指数（AQI），包含PM2.5、PM10、臭氧、一氧化碳等污染物浓度。使用 Open-Meteo Air Quality API，开源免费。需要先用 geocoding_search 获取城市经纬度。', 'HTTP', 'GET', 'https://air-quality-api.open-meteo.com/v1/air-quality?latitude={latitude}&longitude={longitude}&current=pm2_5,pm10,european_aqi,us_aqi,carbon_monoxide,ozone', '{}', NULL, '{"latitude": {"type": "number", "required": true, "description": "城市纬度（WGS84），如北京为 39.9042"}, "longitude": {"type": "number", "required": true, "description": "城市经度（WGS84），如北京为 116.4074"}}', '$.current', 'NONE', '{}', 8000, false, true, '2026-07-04 16:32:05.979324', '2026-07-04 16:32:05.979324');

--
-- Data for Name: cs_intent_tool; Type: TABLE DATA; Schema: cs_conversation; Owner: -
--

INSERT INTO cs_conversation.cs_intent_tool (id, intent_id, tool_id, execution_mode, execution_order, param_mappings) VALUES (1, 1, 3, 'REQUIRED', 0, '{"user_id": {"key": "user_id", "source": "session"}}');
INSERT INTO cs_conversation.cs_intent_tool (id, intent_id, tool_id, execution_mode, execution_order, param_mappings) VALUES (2, 1, 4, 'REQUIRED', 1, '{"order_id": {"key": "order_id", "source": "slot"}}');
INSERT INTO cs_conversation.cs_intent_tool (id, intent_id, tool_id, execution_mode, execution_order, param_mappings) VALUES (3, 2, 4, 'REQUIRED', 0, '{"order_id": {"key": "order_id", "source": "slot"}}');
INSERT INTO cs_conversation.cs_intent_tool (id, intent_id, tool_id, execution_mode, execution_order, param_mappings) VALUES (4, 2, 5, 'OPTIONAL', 1, '{"order_id": {"key": "order_id", "source": "slot"}}');
INSERT INTO cs_conversation.cs_intent_tool (id, intent_id, tool_id, execution_mode, execution_order, param_mappings) VALUES (5, 6, 6, 'REQUIRED', 0, '{"account_id": {"key": "account_id", "source": "slot"}}');
INSERT INTO cs_conversation.cs_intent_tool (id, intent_id, tool_id, execution_mode, execution_order, param_mappings) VALUES (6, 10, 7, 'REQUIRED', 0, '{"city": {"key": "city", "source": "slot"}, "check_in": {"key": "check_in", "source": "slot"}, "check_out": {"key": "check_out", "source": "slot"}}');
INSERT INTO cs_conversation.cs_intent_tool (id, intent_id, tool_id, execution_mode, execution_order, param_mappings) VALUES (7, 13, 9, 'REQUIRED', 1, '{"city": {"key": "city", "source": "slot"}}');
INSERT INTO cs_conversation.cs_intent_tool (id, intent_id, tool_id, execution_mode, execution_order, param_mappings) VALUES (8, 14, 10, 'REQUIRED', 1, '{"city": {"key": "city", "source": "slot"}, "days": {"key": "days", "source": "slot"}}');
INSERT INTO cs_conversation.cs_intent_tool (id, intent_id, tool_id, execution_mode, execution_order, param_mappings) VALUES (9, 15, 11, 'OPTIONAL', 1, '{"latitude": {"key": "geocoding.latitude", "source": "tool_result"}, "longitude": {"key": "geocoding.longitude", "source": "tool_result"}}');
INSERT INTO cs_conversation.cs_intent_tool (id, intent_id, tool_id, execution_mode, execution_order, param_mappings) VALUES (10, 16, 10, 'REQUIRED', 1, '{"city": {"key": "city", "source": "slot"}}');

--
-- Data for Name: cs_session_domain_switch; Type: TABLE DATA; Schema: cs_conversation; Owner: -
--

--
-- Data for Name: flyway_schema_history; Type: TABLE DATA; Schema: cs_conversation; Owner: -
--

INSERT INTO cs_conversation.flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (0, NULL, '<< Flyway Schema Creation >>', 'SCHEMA', '"cs_conversation"', NULL, 'postgres', '2026-06-29 21:54:32.760068', 0, true);
INSERT INTO cs_conversation.flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (1, '1', 'create conversation tables', 'SQL', 'V1__create_conversation_tables.sql', 1610156439, 'postgres', '2026-06-29 21:54:32.778165', 46, true);

--
-- Name: cs_conversation_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: -
--

SELECT pg_catalog.setval('cs_conversation.cs_conversation_id_seq', 196, true);

--
-- Name: cs_conversation_message_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: -
--

SELECT pg_catalog.setval('cs_conversation.cs_conversation_message_id_seq', 783, true);

--
-- Name: cs_domain_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: -
--

SELECT pg_catalog.setval('cs_conversation.cs_domain_id_seq', 6, true);

--
-- Name: cs_intent_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: -
--

SELECT pg_catalog.setval('cs_conversation.cs_intent_id_seq', 17, true);

--
-- Name: cs_intent_slot_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: -
--

SELECT pg_catalog.setval('cs_conversation.cs_intent_slot_id_seq', 11, true);

--
-- Name: cs_intent_tool_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: -
--

SELECT pg_catalog.setval('cs_conversation.cs_intent_tool_id_seq', 10, true);

--
-- Name: cs_session_domain_switch_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: -
--

SELECT pg_catalog.setval('cs_conversation.cs_session_domain_switch_id_seq', 33, true);

--
-- Name: cs_tool_call_log_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: -
--

SELECT pg_catalog.setval('cs_conversation.cs_tool_call_log_id_seq', 12, true);

--
-- Name: cs_tool_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: -
--

SELECT pg_catalog.setval('cs_conversation.cs_tool_id_seq', 11, true);
--
-- PostgreSQL database dump complete
--

-- 默认排班：周一至周五 09:00–18:00，周六周日关闭
INSERT INTO cs_conversation.cs_business_hours_schedule (day_of_week, is_open, time_ranges) VALUES
(1, 1, '[{"start":"09:00","end":"18:00"}]'),
(2, 1, '[{"start":"09:00","end":"18:00"}]'),
(3, 1, '[{"start":"09:00","end":"18:00"}]'),
(4, 1, '[{"start":"09:00","end":"18:00"}]'),
(5, 1, '[{"start":"09:00","end":"18:00"}]'),
(6, 0, '[]'),
(7, 0, '[]')
ON CONFLICT (day_of_week) DO NOTHING;

-- 离线回复消息（写入 cs_auth.system_config，与 agent.welcomeMessage 等配置保持一致）
INSERT INTO cs_auth.system_config (config_key, config_value, config_type, remark)
VALUES ('agent.offlineMessage',
        '您好，当前不在服务时间，我们将在 {nextOpenTime} 恢复服务，感谢您的耐心等待。',
        'CUSTOMER_SERVICE', '非服务时间离线自动回复消息')
ON CONFLICT (config_key) DO NOTHING;

-- 预置标签
INSERT INTO cs_conversation.cs_tag (name, color, source) VALUES
('VIP',    '#F59E0B', 'PRESET'),
('潜在客户', '#10B981', 'PRESET'),
('投诉用户', '#EF4444', 'PRESET'),
('高价值',  '#6366F1', 'PRESET'),
('需跟进',  '#F97316', 'PRESET')
ON CONFLICT (name) DO NOTHING;

-- 默认 SLA 兜底策略（priority=0，匹配所有会话）
INSERT INTO cs_conversation.cs_sla_policy
    (name, is_enabled, priority, match_visitor_tags, match_transfer_tags,
     time_mode, wait_time_target_sec, frt_target_sec, handle_time_target_sec,
     warning_threshold_pct, actions)
VALUES ('默认 SLA', 1, 0, '[]', '[]', 'CALENDAR', 120, 60, 1800, 80,
    '{"recordBreachOnly":true,"sseAlert":true,"autoEscalate":false,"escalateToUserId":null}')
ON CONFLICT (name) DO NOTHING;
