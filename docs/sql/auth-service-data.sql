-- auth-service (cs_auth data)
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
-- Data for Name: ai_model_config; Type: TABLE DATA; Schema: cs_auth; Owner: -
--

INSERT INTO cs_auth.ai_model_config (id, name, provider, api_protocol, remark, base_url, api_key_enc, model_name, temperature, max_tokens, timeout_sec, is_default, is_enabled, created_by, created_at, updated_at, deleted_at, model_type) VALUES (9, 'tongyi-intent-detect-v3', 'CUSTOM', 'OPENAI_COMPATIBLE', NULL, 'https://llm-abgxi9yilg0zfoev.cn-beijing.maas.aliyuncs.com/compatible-mode/v1', 'PLAINTEXT:sk-53e959ca40084e06953073c5c529e3ef', 'tongyi-intent-detect-v3', 0.00, 32, 5, false, true, 1001, '2026-07-10 00:40:08.402486', '2026-07-10 00:49:46.093174', NULL, 'ROUTER');
INSERT INTO cs_auth.ai_model_config (id, name, provider, api_protocol, remark, base_url, api_key_enc, model_name, temperature, max_tokens, timeout_sec, is_default, is_enabled, created_by, created_at, updated_at, deleted_at, model_type) VALUES (10, 'tongyi-xiaomi-analysis-flash', 'CUSTOM', 'OPENAI_COMPATIBLE', NULL, 'https://llm-abgxi9yilg0zfoev.cn-beijing.maas.aliyuncs.com/compatible-mode/v1', 'PLAINTEXT:sk-53e959ca40084e06953073c5c529e3ef', 'tongyi-xiaomi-analysis-flash', 0.00, 32, 5, true, true, 1001, '2026-07-10 00:49:37.09858', '2026-07-10 00:49:46.093174', NULL, 'ROUTER');
INSERT INTO cs_auth.ai_model_config (id, name, provider, api_protocol, remark, base_url, api_key_enc, model_name, temperature, max_tokens, timeout_sec, is_default, is_enabled, created_by, created_at, updated_at, deleted_at, model_type) VALUES (2, '本地 BGE-M3', 'Custom', 'OPENAI_COMPATIBLE', NULL, 'http://localhost:11434/v1', 'PLAINTEXT:', 'bge-m3', 0.00, 0, 60, true, true, NULL, '2026-07-02 17:21:17.73629', '2026-07-09 21:30:07.480823', NULL, 'EMBEDDING');
INSERT INTO cs_auth.ai_model_config (id, name, provider, api_protocol, remark, base_url, api_key_enc, model_name, temperature, max_tokens, timeout_sec, is_default, is_enabled, created_by, created_at, updated_at, deleted_at, model_type) VALUES (1, '天翼云 DeepSeek-V4-Pro', 'CTYUN', 'OPENAI_COMPATIBLE', '天翼云 AI 平台 DeepSeek-V4-Flash 模型，默认对话模型', 'https://wishub-x6.ctyun.cn/v1', 'PLAINTEXT:c4747b2f3e3e49308b5fb0ba256cb70e', 'DeepSeek-V4-Pro', 0.70, 4096, 60, true, true, 1001, '2026-07-02 15:33:05.115131', '2026-07-10 00:26:59.38003', NULL, 'CHAT');
INSERT INTO cs_auth.ai_model_config (id, name, provider, api_protocol, remark, base_url, api_key_enc, model_name, temperature, max_tokens, timeout_sec, is_default, is_enabled, created_by, created_at, updated_at, deleted_at, model_type) VALUES (8, 'qwen3.6-flash', 'CUSTOM', 'OPENAI_COMPATIBLE', NULL, 'https://llm-abgxi9yilg0zfoev.cn-beijing.maas.aliyuncs.com/compatible-mode/v1', 'PLAINTEXT:sk-53e959ca40084e06953073c5c529e3ef', 'qwen3.6-flash-2026-04-16', 0.00, 32, 5, false, true, 1001, '2026-07-10 00:39:28.402335', '2026-07-10 00:39:28.404229', NULL, 'ROUTER');
INSERT INTO cs_auth.ai_model_config (id, name, provider, api_protocol, remark, base_url, api_key_enc, model_name, temperature, max_tokens, timeout_sec, is_default, is_enabled, created_by, created_at, updated_at, deleted_at, model_type) VALUES (7, '意图识别和槽位填充模型', 'OPENAI', 'OPENAI_COMPATIBLE', NULL, 'http://192.168.1.8:11434/v1', 'PLAINTEXT:sk-53e959ca40084e06953073c5c529e3ef', 'qwen:4b', 0.00, 32, 5, false, true, 1001, '2026-07-06 23:04:45.988999', '2026-07-10 00:40:12.55508', NULL, 'ROUTER');

--
-- Data for Name: flyway_schema_history; Type: TABLE DATA; Schema: cs_auth; Owner: -
--

INSERT INTO cs_auth.flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (1, '0', '<< Flyway Baseline >>', 'BASELINE', '<< Flyway Baseline >>', NULL, 'postgres', '2026-06-29 15:07:40.499792', 0, true);
INSERT INTO cs_auth.flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (2, '1', 'init cs auth schema', 'SQL', 'V1__init_cs_auth_schema.sql', 0, 'postgres', '2026-06-29 07:09:05.614612', 100, true);
INSERT INTO cs_auth.flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (3, '2', 'seed menu data', 'SQL', 'V2__seed_menu_data.sql', -1038647149, 'postgres', '2026-06-29 15:09:16.154434', 17, true);
INSERT INTO cs_auth.flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (4, '3', 'seed test users', 'SQL', 'V3__seed_test_users.sql', -1914365077, 'postgres', '2026-06-29 15:09:16.211396', 24, true);

--
-- Data for Name: sys_dept; Type: TABLE DATA; Schema: cs_auth; Owner: -
--

--
-- Data for Name: sys_menu; Type: TABLE DATA; Schema: cs_auth; Owner: -
--

INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (1, 0, 'DIRECTORY', '概览', 'Dashboard', '/dashboard', NULL, 'lucide:layout-dashboard', 1, true, false, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (3, 1, 'MENU', '工作台', 'DashboardWorkspace', '/dashboard/workspace', 'dashboard/workspace/index', 'carbon:workspace', 2, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (102, 100, 'MENU', '知识库', 'CustomerServiceKnowledge', '/customerservice/knowledge', 'customerservice/knowledge/index', 'lucide:book-open', 2, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (103, 100, 'MENU', '座席工作台', 'CustomerServiceAgent', '/customerservice/agent', 'customerservice/agent/index', 'lucide:headphones', 3, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (110, 102, 'BUTTON', '上传文档', 'knowledge:doc:upload', NULL, NULL, NULL, 1, false, false, false, NULL, 'knowledge:doc:upload', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (111, 102, 'BUTTON', '审核文档', 'knowledge:doc:review', NULL, NULL, NULL, 2, false, false, false, NULL, 'knowledge:doc:review', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (112, 102, 'BUTTON', '下线文档', 'knowledge:doc:offline', NULL, NULL, NULL, 3, false, false, false, NULL, 'knowledge:doc:offline', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (113, 102, 'BUTTON', '删除文档', 'knowledge:doc:delete', NULL, NULL, NULL, 4, false, false, false, NULL, 'knowledge:doc:delete', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (120, 103, 'BUTTON', '接入会话', 'agent:session:accept', NULL, NULL, NULL, 1, false, false, false, NULL, 'agent:session:accept', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (121, 103, 'BUTTON', '结束会话', 'agent:session:close', NULL, NULL, NULL, 2, false, false, false, NULL, 'agent:session:close', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (122, 103, 'BUTTON', '转交会话', 'agent:session:transfer', NULL, NULL, NULL, 3, false, false, false, NULL, 'agent:session:transfer', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (200, 0, 'DIRECTORY', '系统管理', 'System', '/system', NULL, 'lucide:settings', 90, true, false, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (201, 200, 'MENU', '用户管理', 'SystemUser', '/system/user', 'system/user/index', 'lucide:users', 1, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (202, 200, 'MENU', '角色管理', 'SystemRole', '/system/role', 'system/role/index', 'lucide:shield', 2, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (203, 200, 'MENU', '菜单管理', 'SystemMenu', '/system/menu', 'system/menu/index', 'lucide:layout-list', 3, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (210, 201, 'BUTTON', '新增用户', 'system:user:create', NULL, NULL, NULL, 1, false, false, false, NULL, 'system:user:create', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (211, 201, 'BUTTON', '编辑用户', 'system:user:update', NULL, NULL, NULL, 2, false, false, false, NULL, 'system:user:update', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (212, 201, 'BUTTON', '删除用户', 'system:user:delete', NULL, NULL, NULL, 3, false, false, false, NULL, 'system:user:delete', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (213, 201, 'BUTTON', '重置密码', 'system:user:reset-pwd', NULL, NULL, NULL, 4, false, false, false, NULL, 'system:user:reset-pwd', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (214, 201, 'BUTTON', '分配角色', 'system:user:assign-role', NULL, NULL, NULL, 5, false, false, false, NULL, 'system:user:assign-role', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (220, 202, 'BUTTON', '新增角色', 'system:role:create', NULL, NULL, NULL, 1, false, false, false, NULL, 'system:role:create', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (221, 202, 'BUTTON', '编辑角色', 'system:role:update', NULL, NULL, NULL, 2, false, false, false, NULL, 'system:role:update', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (222, 202, 'BUTTON', '删除角色', 'system:role:delete', NULL, NULL, NULL, 3, false, false, false, NULL, 'system:role:delete', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (223, 202, 'BUTTON', '分配菜单', 'system:role:assign-menu', NULL, NULL, NULL, 4, false, false, false, NULL, 'system:role:assign-menu', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (900, 0, 'MENU', '关于', 'About', '/about', '_core/about/index', 'lucide:copyright', 99, true, false, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (204, 200, 'MENU', '部门管理', 'SystemDept', '/system/dept', '_core/fallback/coming-soon', 'lucide:building-2', 4, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-07-02 15:29:51.400768');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (2, 1, 'MENU', '分析页', 'DashboardAnalysis', '/dashboard/analysis', 'dashboard/analytics/index', 'lucide:area-chart', 1, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-07-02 15:30:07.084421');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (205, 200, 'MENU', 'AI 模型配置', 'SystemAiModel', '/system/ai-model', 'system/ai-model/index', 'lucide:cpu', 5, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-02 17:08:00.32996', '2026-07-02 17:08:00.32996');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (230, 205, 'BUTTON', '新增配置', 'system:ai-model:create', NULL, NULL, NULL, 1, false, false, false, NULL, 'system:ai-model:create', 'active', NULL, NULL, '2026-07-02 17:08:16.290288', '2026-07-02 17:08:16.290288');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (231, 205, 'BUTTON', '编辑配置', 'system:ai-model:update', NULL, NULL, NULL, 2, false, false, false, NULL, 'system:ai-model:update', 'active', NULL, NULL, '2026-07-02 17:08:16.290288', '2026-07-02 17:08:16.290288');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (232, 205, 'BUTTON', '删除配置', 'system:ai-model:delete', NULL, NULL, NULL, 3, false, false, false, NULL, 'system:ai-model:delete', 'active', NULL, NULL, '2026-07-02 17:08:16.290288', '2026-07-02 17:08:16.290288');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (233, 205, 'BUTTON', '设为默认', 'system:ai-model:set-default', NULL, NULL, NULL, 4, false, false, false, NULL, 'system:ai-model:set-default', 'active', NULL, NULL, '2026-07-02 17:08:16.290288', '2026-07-02 17:08:16.290288');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (104, 100, 'DIRECTORY', 'DIT配置', 'CustomerServiceDIT', '/customerservice/dit', NULL, 'lucide:settings-2', 40, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749', '2026-07-04 15:48:09.284749');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (105, 104, 'MENU', '领域与意图', 'CustomerServiceDITDomains', '/customerservice/dit/domains', 'customerservice/dit/domains/index', 'lucide:layers', 10, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749', '2026-07-04 15:48:09.284749');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (106, 104, 'MENU', '工具注册中心', 'CustomerServiceDITTools', '/customerservice/dit/tools', 'customerservice/dit/tools/index', 'lucide:wrench', 20, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749', '2026-07-04 15:48:09.284749');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (130, 105, 'BUTTON', '新建领域', 'dit:domain:create', NULL, NULL, NULL, 0, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749', '2026-07-04 15:48:09.284749');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (131, 105, 'BUTTON', '编辑领域', 'dit:domain:update', NULL, NULL, NULL, 0, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749', '2026-07-04 15:48:09.284749');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (132, 105, 'BUTTON', '删除领域', 'dit:domain:delete', NULL, NULL, NULL, 0, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749', '2026-07-04 15:48:09.284749');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (133, 105, 'BUTTON', '管理意图', 'dit:intent:manage', NULL, NULL, NULL, 0, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749', '2026-07-04 15:48:09.284749');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (134, 105, 'BUTTON', '管理槽位', 'dit:slot:manage', NULL, NULL, NULL, 0, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749', '2026-07-04 15:48:09.284749');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (135, 106, 'BUTTON', '注册工具', 'dit:tool:create', NULL, NULL, NULL, 0, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749', '2026-07-04 15:48:09.284749');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (136, 106, 'BUTTON', '编辑工具', 'dit:tool:update', NULL, NULL, NULL, 0, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749', '2026-07-04 15:48:09.284749');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (137, 106, 'BUTTON', '删除工具', 'dit:tool:delete', NULL, NULL, NULL, 0, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749', '2026-07-04 15:48:09.284749');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (138, 106, 'BUTTON', '测试工具', 'dit:tool:test', NULL, NULL, NULL, 0, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749', '2026-07-04 15:48:09.284749');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (901, 200, 'MENU', '系统参数配置', 'system:config', '/system/config', 'system/config/index', 'lucide:sliders', 90, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-12 04:31:30.295086', '2026-07-12 06:47:43.024942');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (902, 100, 'MENU', '客服参数配置', 'customerservice:config', '/customerservice/config', 'system/config/index', 'lucide:sliders-horizontal', 90, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-12 04:31:30.295086', '2026-07-12 06:47:43.024942');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (101, 100, 'MENU', '对话', 'CustomerServiceChat', '/customerservice/chat', 'customerservice/chat/index', 'lucide:message-circle', 1, false, true, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-07-12 14:52:17.780293');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (100, 0, 'DIRECTORY', '智能客服', 'CustomerService', '/customerservice', NULL, 'lucide:bot', 10, true, false, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-07-12 14:52:12.963409');

--
-- Data for Name: sys_permission; Type: TABLE DATA; Schema: cs_auth; Owner: -
--

INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (1, 'knowledge:doc:upload', '上传文档', 'knowledge', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (2, 'knowledge:doc:review', '审核文档', 'knowledge', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (3, 'knowledge:doc:offline', '下线文档', 'knowledge', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (4, 'knowledge:doc:delete', '删除文档', 'knowledge', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (5, 'agent:session:accept', '接入会话', 'agent', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (6, 'agent:session:close', '结束会话', 'agent', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (7, 'agent:session:transfer', '转交会话', 'agent', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (8, 'system:user:create', '新增用户', 'system', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (9, 'system:user:update', '编辑用户', 'system', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (10, 'system:user:delete', '删除用户', 'system', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (11, 'system:user:reset-pwd', '重置密码', 'system', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (12, 'system:user:assign-role', '分配角色', 'system', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (13, 'system:role:create', '新增角色', 'system', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (14, 'system:role:update', '编辑角色', 'system', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (15, 'system:role:delete', '删除角色', 'system', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (16, 'system:role:assign-menu', '分配菜单', 'system', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (50, 'system:ai-model:create', '新增AI模型配置', 'system', NULL, '2026-07-02 17:08:16.290288');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (51, 'system:ai-model:update', '编辑AI模型配置', 'system', NULL, '2026-07-02 17:08:16.290288');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (52, 'system:ai-model:delete', '删除AI模型配置', 'system', NULL, '2026-07-02 17:08:16.290288');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (53, 'system:ai-model:set-default', '设为默认AI模型', 'system', NULL, '2026-07-02 17:08:16.290288');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (54, 'system:config:list', '系统配置-查询', 'system_config', '查看系统配置列表及详情', '2026-07-12 04:31:30.288745');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (55, 'system:config:create', '系统配置-新增', 'system_config', '新增系统配置项', '2026-07-12 04:31:30.288745');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (56, 'system:config:update', '系统配置-编辑', 'system_config', '修改配置值及启用状态', '2026-07-12 04:31:30.288745');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (57, 'system:config:delete', '系统配置-删除', 'system_config', '软删除系统配置项', '2026-07-12 04:31:30.288745');

--
-- Data for Name: sys_role; Type: TABLE DATA; Schema: cs_auth; Owner: -
--

INSERT INTO cs_auth.sys_role (id, role_key, role_name, description, is_system, status, created_at, updated_at) VALUES (10, 'super_admin', '超级管理员', NULL, true, 'active', '2026-06-29 07:04:09.972562', '2026-06-29 07:04:09.972562');
INSERT INTO cs_auth.sys_role (id, role_key, role_name, description, is_system, status, created_at, updated_at) VALUES (11, 'kf_manager', '客服管理员', NULL, false, 'active', '2026-06-29 07:04:09.972562', '2026-06-29 07:04:09.972562');
INSERT INTO cs_auth.sys_role (id, role_key, role_name, description, is_system, status, created_at, updated_at) VALUES (12, 'kf_staff', '普通客服', NULL, false, 'active', '2026-06-29 07:04:09.972562', '2026-06-29 07:04:09.972562');

--
-- Data for Name: sys_role_data_scope; Type: TABLE DATA; Schema: cs_auth; Owner: -
--

INSERT INTO cs_auth.sys_role_data_scope (id, role_id, scope_type, custom_dept_ids, created_at, updated_at) VALUES (1, 10, 'ALL', '[]', '2026-06-29 07:04:09.974783', '2026-06-29 15:09:16.215976');
INSERT INTO cs_auth.sys_role_data_scope (id, role_id, scope_type, custom_dept_ids, created_at, updated_at) VALUES (2, 11, 'DEPT_TREE', '[]', '2026-06-29 07:04:09.974783', '2026-06-29 15:09:16.215976');
INSERT INTO cs_auth.sys_role_data_scope (id, role_id, scope_type, custom_dept_ids, created_at, updated_at) VALUES (3, 12, 'SELF', '[]', '2026-06-29 07:04:09.974783', '2026-06-29 15:09:16.215976');

--
-- Data for Name: sys_role_menu; Type: TABLE DATA; Schema: cs_auth; Owner: -
--

INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 100, '2026-06-29 07:04:09.977047');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 101, '2026-06-29 07:04:09.977047');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 102, '2026-06-29 07:04:09.977047');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 103, '2026-06-29 07:04:09.977047');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 110, '2026-06-29 07:04:09.977047');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 111, '2026-06-29 07:04:09.977047');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 112, '2026-06-29 07:04:09.977047');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 113, '2026-06-29 07:04:09.977047');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 120, '2026-06-29 07:04:09.977047');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 121, '2026-06-29 07:04:09.977047');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 122, '2026-06-29 07:04:09.977047');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (12, 100, '2026-06-29 07:04:09.977935');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (12, 101, '2026-06-29 07:04:09.977935');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 104, '2026-07-04 15:48:09.318584');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 105, '2026-07-04 15:48:09.318584');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 106, '2026-07-04 15:48:09.318584');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 133, '2026-07-04 15:48:09.318584');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 134, '2026-07-04 15:48:09.318584');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 138, '2026-07-04 15:48:09.318584');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 1, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 2, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 3, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 100, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 101, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 102, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 103, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 110, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 111, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 112, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 113, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 120, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 121, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 122, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 200, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 201, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 202, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 203, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 204, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 210, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 211, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 212, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 213, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 214, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 220, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 221, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 222, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 223, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 900, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 205, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 230, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 231, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 232, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 233, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 104, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 105, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 106, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 130, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 131, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 132, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 133, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 134, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 135, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 136, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 137, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 138, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 901, '2026-07-12 04:31:30.31534');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 902, '2026-07-12 04:31:30.31534');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 902, '2026-07-12 04:31:30.31534');

--
-- Data for Name: sys_role_permission; Type: TABLE DATA; Schema: cs_auth; Owner: -
--

INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 1);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 2);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 3);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 4);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 5);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 6);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 7);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 8);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 9);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 10);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 11);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 12);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 13);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 14);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 15);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 16);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 50);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 51);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 52);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 53);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 54);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 55);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 56);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 57);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (11, 54);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (11, 55);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (11, 56);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (11, 57);

--
-- Data for Name: sys_user; Type: TABLE DATA; Schema: cs_auth; Owner: -
--

-- 默认密码：Test@123456（BCrypt cost=10）
INSERT INTO cs_auth.sys_user (id, username, display_name, email, phone, password_hash, status, provider, login_fail_count, locked_until, must_change_password, password_changed_at, password_history, last_login_at, last_login_ip, dept_id, dept_name, deleted_at, created_at, updated_at) VALUES (1003, 'kfstaff', '普通客服', 'kfstaff@example.com', NULL, '$2a$10$Eb4W1viRpoA9Bt1hh7tiJuJYU4A4cMSPviCw8Jyl/9unpvQRzI0qO', 'active', 'local', 0, NULL, false, '2026-07-19 00:00:00.000000', '[]', '2026-07-10 10:52:03.648093', '0:0:0:0:0:0:0:1', NULL, NULL, NULL, '2026-06-29 07:04:09.978982', '2026-07-10 18:52:03.648857');
INSERT INTO cs_auth.sys_user (id, username, display_name, email, phone, password_hash, status, provider, login_fail_count, locked_until, must_change_password, password_changed_at, password_history, last_login_at, last_login_ip, dept_id, dept_name, deleted_at, created_at, updated_at) VALUES (1001, 'superadmin', '超级管理员', 'superadmin@example.com', NULL, '$2a$10$Eb4W1viRpoA9Bt1hh7tiJuJYU4A4cMSPviCw8Jyl/9unpvQRzI0qO', 'active', 'local', 0, NULL, false, '2026-07-19 00:00:00.000000', '[]', '2026-07-12 10:52:37.539064', '0:0:0:0:0:0:0:1', NULL, NULL, NULL, '2026-06-29 07:04:09.978982', '2026-07-12 18:52:37.668473');
INSERT INTO cs_auth.sys_user (id, username, display_name, email, phone, password_hash, status, provider, login_fail_count, locked_until, must_change_password, password_changed_at, password_history, last_login_at, last_login_ip, dept_id, dept_name, deleted_at, created_at, updated_at) VALUES (1002, 'kfmanager', '客服管理员', 'kfmanager@example.com', NULL, '$2a$10$Eb4W1viRpoA9Bt1hh7tiJuJYU4A4cMSPviCw8Jyl/9unpvQRzI0qO', 'active', 'local', 0, NULL, false, '2026-07-19 00:00:00.000000', '[]', '2026-06-29 16:40:57.865042', '127.0.0.1', NULL, NULL, NULL, '2026-06-29 07:04:09.978982', '2026-06-30 00:40:57.781134');

--
-- Data for Name: sys_user_dept; Type: TABLE DATA; Schema: cs_auth; Owner: -
--

--
-- Data for Name: sys_user_role; Type: TABLE DATA; Schema: cs_auth; Owner: -
--

INSERT INTO cs_auth.sys_user_role (user_id, role_id, granted_at, granted_by) VALUES (1001, 10, '2026-06-29 07:04:09.982149', NULL);
INSERT INTO cs_auth.sys_user_role (user_id, role_id, granted_at, granted_by) VALUES (1002, 11, '2026-06-29 07:04:09.982149', NULL);
INSERT INTO cs_auth.sys_user_role (user_id, role_id, granted_at, granted_by) VALUES (1003, 12, '2026-06-29 07:04:09.982149', NULL);

--
-- Data for Name: system_config; Type: TABLE DATA; Schema: cs_auth; Owner: -
--

INSERT INTO cs_auth.system_config (id, config_key, config_value, config_type, description, is_enabled, created_at, updated_at, deleted_at) VALUES (1, 'agent.maxConcurrent', '5', 'CUSTOMER_SERVICE', '单个座席同时接待的最大会话数。超出时系统拒绝新分配。取值范围：1–50', true, '2026-07-12 04:31:30.280237', '2026-07-12 04:31:30.280237', NULL);
INSERT INTO cs_auth.system_config (id, config_key, config_value, config_type, description, is_enabled, created_at, updated_at, deleted_at) VALUES (2, 'agent.welcomeMessage', '您好，感谢联系我们，请问有什么可以帮助您？', 'CUSTOMER_SERVICE', '会话建立时后端自动插入的欢迎消息内容', true, '2026-07-12 04:31:30.280237', '2026-07-12 04:31:30.280237', NULL);
INSERT INTO cs_auth.system_config (id, config_key, config_value, config_type, description, is_enabled, created_at, updated_at, deleted_at) VALUES (3, 'knowledge.searchTopK', '5', 'CUSTOMER_SERVICE', 'RAG 检索时返回的最大相关片段数（TopK）。取值范围：1–20', true, '2026-07-12 04:31:30.280237', '2026-07-12 04:31:30.280237', NULL);
INSERT INTO cs_auth.system_config (id, config_key, config_value, config_type, description, is_enabled, created_at, updated_at, deleted_at) VALUES (4, 'knowledge.uploadMaxFileSizeMb', '20', 'CUSTOMER_SERVICE', '知识库文件上传的单文件大小上限（单位：MB）。取值范围：1–200', true, '2026-07-12 04:31:30.280237', '2026-07-12 04:31:30.280237', NULL);
INSERT INTO cs_auth.system_config (id, config_key, config_value, config_type, description, is_enabled, created_at, updated_at, deleted_at) VALUES (5, 'prompt.agent.suggestion', '你是一名专业客服，请根据以下对话历史和知识库内容，为座席生成 3 条简洁的回复建议。

对话历史：
{history}

知识库参考：
{context}', 'CUSTOMER_SERVICE', '座席建议回复的 prompt 模板。占位符：{history}、{context}', true, '2026-07-12 04:31:30.280237', '2026-07-12 04:31:30.280237', NULL);
INSERT INTO cs_auth.system_config (id, config_key, config_value, config_type, description, is_enabled, created_at, updated_at, deleted_at) VALUES (6, 'prompt.kb.qa', '你是一名专业客服助手，请根据以下知识库内容回答用户问题。如果知识库中没有相关信息，请如实告知。

知识库内容：
{context}

用户问题：{question}', 'CUSTOMER_SERVICE', '知识库问答的 prompt 模板。占位符：{context}、{question}', true, '2026-07-12 04:31:30.280237', '2026-07-12 04:31:30.280237', NULL);
INSERT INTO cs_auth.system_config (id, config_key, config_value, config_type, description, is_enabled, created_at, updated_at, deleted_at) VALUES (7, 'prompt.visitor.autoReply', '你是一名智能客服，请根据以下对话历史和知识库内容，自动回复访客的最新消息。回复要简洁、友好、专业。

知识库内容：
{context}

对话历史：
{history}', 'CUSTOMER_SERVICE', '访客自动回复的 prompt 模板。占位符：{context}、{history}', true, '2026-07-12 04:31:30.280237', '2026-07-12 04:31:30.280237', NULL);
INSERT INTO cs_auth.system_config (id, config_key, config_value, config_type, description, is_enabled, created_at, updated_at, deleted_at) VALUES (8, 'prompt.session.summary', '请根据以下客服对话记录，生成一份简洁的会话摘要，包含：用户主要问题、解决方案、是否已解决。

对话记录：
{history}', 'CUSTOMER_SERVICE', '会话结束后生成摘要的 prompt 模板。占位符：{history}', true, '2026-07-12 04:31:30.280237', '2026-07-12 04:31:30.280237', NULL);
INSERT INTO cs_auth.system_config (id, config_key, config_value, config_type, description, is_enabled, created_at, updated_at, deleted_at) VALUES (9, 'prompt.intent.classify', '请分析用户消息的意图，从以下类别中选择最匹配的一个：{intents}。

用户消息：{message}

只需返回类别名称，不需要解释。', 'CUSTOMER_SERVICE', '意图识别分类的 prompt 模板。占位符：{intents}、{message}', true, '2026-07-12 04:31:30.280237', '2026-07-12 04:31:30.280237', NULL);
INSERT INTO cs_auth.system_config (id, config_key, config_value, config_type, description, is_enabled, created_at, updated_at, deleted_at) VALUES (10, 'dashboard.recentLimit', '10', 'SYSTEM', '仪表盘"最近记录"查询的 SQL LIMIT 值。取值范围：5–100', true, '2026-07-12 04:31:30.280237', '2026-07-12 04:31:30.280237', NULL);
INSERT INTO cs_auth.system_config (id, config_key, config_value, config_type, description, is_enabled, created_at, updated_at, deleted_at) VALUES (11, 'complexity.simpleMaxMessages', '5', 'CUSTOMER_SERVICE', '会话复杂度分桶：消息数 ≤ 此值为「简单」，取值范围 1–20', true, '2026-07-12 05:12:53.167476', '2026-07-12 05:12:53.167476', NULL);
INSERT INTO cs_auth.system_config (id, config_key, config_value, config_type, description, is_enabled, created_at, updated_at, deleted_at) VALUES (12, 'complexity.mediumMaxMessages', '15', 'CUSTOMER_SERVICE', '会话复杂度分桶：消息数 ≤ 此值为「中等」，超出则为「复杂」，取值范围 6–100', true, '2026-07-12 05:12:53.167476', '2026-07-12 05:12:53.167476', NULL);
INSERT INTO cs_auth.system_config (id, config_key, config_value, config_type, description, is_enabled, created_at, updated_at, deleted_at) VALUES (13, 'routing.config', '{
  "intent": {
    "embeddingEnabled": false,
    "embeddingThreshold": 0.75,
    "minLlmConfidence": 0.0,
    "maxExamplesToInject": 5
  },
  "domain": {
    "ruleEnabled": true
  }
}', 'CUSTOMER_SERVICE', '意图路由级联配置（JSON）', true, '2026-07-14 00:00:00.000000', '2026-07-14 00:00:00.000000', NULL);

--
-- Name: ai_model_config_id_seq; Type: SEQUENCE SET; Schema: cs_auth; Owner: -
--

SELECT pg_catalog.setval('cs_auth.ai_model_config_id_seq', 10, true);

--
-- Name: sys_dept_id_seq; Type: SEQUENCE SET; Schema: cs_auth; Owner: -
--

SELECT pg_catalog.setval('cs_auth.sys_dept_id_seq', 1, false);

--
-- Name: sys_menu_id_seq; Type: SEQUENCE SET; Schema: cs_auth; Owner: -
--

SELECT pg_catalog.setval('cs_auth.sys_menu_id_seq', 902, true);

--
-- Name: sys_permission_id_seq; Type: SEQUENCE SET; Schema: cs_auth; Owner: -
--

SELECT pg_catalog.setval('cs_auth.sys_permission_id_seq', 57, true);

--
-- Name: sys_role_data_scope_id_seq; Type: SEQUENCE SET; Schema: cs_auth; Owner: -
--

SELECT pg_catalog.setval('cs_auth.sys_role_data_scope_id_seq', 36, true);

--
-- Name: sys_role_id_seq; Type: SEQUENCE SET; Schema: cs_auth; Owner: -
--

SELECT pg_catalog.setval('cs_auth.sys_role_id_seq', 13, true);

--
-- Name: sys_user_id_seq; Type: SEQUENCE SET; Schema: cs_auth; Owner: -
--

SELECT pg_catalog.setval('cs_auth.sys_user_id_seq', 1003, true);

--
-- Name: system_config_id_seq; Type: SEQUENCE SET; Schema: cs_auth; Owner: -
--

SELECT pg_catalog.setval('cs_auth.system_config_id_seq', 13, true);
--
-- PostgreSQL database dump complete
--
