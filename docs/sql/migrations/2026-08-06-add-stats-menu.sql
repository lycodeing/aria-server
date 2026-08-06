-- =============================================================================
-- 新增"统计分析"菜单：/stats 目录 + 意图分类/RAG质量/LLM成本 三个子页
-- 日期：2026-08-06
--
-- 背景：
-- P0 可观测性改造新增了三个后台统计 API（AdminStatsController，
-- /api/v1/admin/stats/{intent-classification|rag-quality|llm-cost}），
-- 三个端点均由 @SaCheckPermission("system:session:query") 守护。
-- 本迁移在菜单表补齐侧边栏入口，并把访问权授予 super_admin(10) 与 kf_manager(11)。
--
-- 结构（顶级目录 + 3 个叶子 MENU）：
--   [249] DIRECTORY 统计分析 /stats
--     ├─ [250] MENU 意图分类  /stats/intent    → stats/intent/index
--     ├─ [251] MENU RAG质量   /stats/rag       → stats/rag/index
--     └─ [252] MENU LLM成本   /stats/llm-cost  → stats/llm-cost/index
--
-- 注意事项：
-- 1. component 路径为后端约定字符串（前端 vben-admin 需存在对应视图组件目录），
--    命名沿用现有 dashboard/analytics/index 风格：stats/<page>/index。
-- 2. 三个统计 API 复用已有权限 system:session:query（sys_permission id=60），
--    不新增 permission。但 kf_manager(11) 当前未持有该权限，只加菜单会导致点进去 401，
--    故本迁移额外把 permission 60 授予 role 11（super_admin 10 已持有）。
-- 3. 菜单页(MENU)本身 permission_key 留 NULL，访问由 sys_role_menu 控制；
--    API 权限由 system:session:query 控制。两者需同时到位 kf_manager 才能真正使用。
-- 4. 全部 INSERT ... ON CONFLICT 幂等，可安全重复执行。
--    执行方式：docker exec -i ai-cs-postgres psql -U postgres -d aria_cs -f 本文件
--    （或 psql -U postgres -d aria_cs -f 本文件）
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 新增菜单项
-- -----------------------------------------------------------------------------

-- [249] 统计分析（顶级目录，component 为 NULL）
INSERT INTO cs_auth.sys_menu
    (id, parent_id, menu_type, menu_name, menu_key, path, component, icon,
     sort_order, is_visible, is_cache, is_external, redirect, permission_key,
     status, remark, created_by, created_at, updated_at)
VALUES
    (249, 0, 'DIRECTORY', '统计分析', 'Stats', '/stats',
     NULL, 'lucide:bar-chart-3', 25, true, true, false, NULL, NULL,
     'active', 'P0 可观测性统计：意图分类命中率 / RAG检索质量 / LLM Token成本', NULL, NOW(), NOW())
ON CONFLICT (id) DO UPDATE SET
    parent_id  = EXCLUDED.parent_id,
    menu_type  = EXCLUDED.menu_type,
    menu_name  = EXCLUDED.menu_name,
    menu_key   = EXCLUDED.menu_key,
    path       = EXCLUDED.path,
    icon       = EXCLUDED.icon,
    sort_order = EXCLUDED.sort_order,
    is_visible = EXCLUDED.is_visible,
    updated_at = NOW();

-- [250] 意图分类（/stats/intent）
INSERT INTO cs_auth.sys_menu
    (id, parent_id, menu_type, menu_name, menu_key, path, component, icon,
     sort_order, is_visible, is_cache, is_external, redirect, permission_key,
     status, remark, created_by, created_at, updated_at)
VALUES
    (250, 249, 'MENU', '意图分类', 'StatsIntent', '/stats/intent',
     'stats/intent/index', 'lucide:git-branch', 1, true, true, false, NULL, NULL,
     'active', 'DIT 三层意图识别命中率与延迟报表', NULL, NOW(), NOW())
ON CONFLICT (id) DO UPDATE SET
    parent_id  = EXCLUDED.parent_id,
    menu_type  = EXCLUDED.menu_type,
    menu_name  = EXCLUDED.menu_name,
    menu_key   = EXCLUDED.menu_key,
    path       = EXCLUDED.path,
    component  = EXCLUDED.component,
    icon       = EXCLUDED.icon,
    sort_order = EXCLUDED.sort_order,
    is_visible = EXCLUDED.is_visible,
    updated_at = NOW();

-- [251] RAG质量（/stats/rag）
INSERT INTO cs_auth.sys_menu
    (id, parent_id, menu_type, menu_name, menu_key, path, component, icon,
     sort_order, is_visible, is_cache, is_external, redirect, permission_key,
     status, remark, created_by, created_at, updated_at)
VALUES
    (251, 249, 'MENU', 'RAG质量', 'StatsRag', '/stats/rag',
     'stats/rag/index', 'lucide:search-check', 2, true, true, false, NULL, NULL,
     'active', 'RAG 检索质量与知识覆盖度（miss 率 / top1 分数）报表', NULL, NOW(), NOW())
ON CONFLICT (id) DO UPDATE SET
    parent_id  = EXCLUDED.parent_id,
    menu_type  = EXCLUDED.menu_type,
    menu_name  = EXCLUDED.menu_name,
    menu_key   = EXCLUDED.menu_key,
    path       = EXCLUDED.path,
    component  = EXCLUDED.component,
    icon       = EXCLUDED.icon,
    sort_order = EXCLUDED.sort_order,
    is_visible = EXCLUDED.is_visible,
    updated_at = NOW();

-- [252] LLM成本（/stats/llm-cost）
INSERT INTO cs_auth.sys_menu
    (id, parent_id, menu_type, menu_name, menu_key, path, component, icon,
     sort_order, is_visible, is_cache, is_external, redirect, permission_key,
     status, remark, created_by, created_at, updated_at)
VALUES
    (252, 249, 'MENU', 'LLM成本', 'StatsLlmCost', '/stats/llm-cost',
     'stats/llm-cost/index', 'lucide:coins', 3, true, true, false, NULL, NULL,
     'active', 'LLM Token 消耗与成本（按模型/调用类型）报表', NULL, NOW(), NOW())
ON CONFLICT (id) DO UPDATE SET
    parent_id  = EXCLUDED.parent_id,
    menu_type  = EXCLUDED.menu_type,
    menu_name  = EXCLUDED.menu_name,
    menu_key   = EXCLUDED.menu_key,
    path       = EXCLUDED.path,
    component  = EXCLUDED.component,
    icon       = EXCLUDED.icon,
    sort_order = EXCLUDED.sort_order,
    is_visible = EXCLUDED.is_visible,
    updated_at = NOW();

-- -----------------------------------------------------------------------------
-- 2. sys_role_menu — 菜单授权（幂等）
-- -----------------------------------------------------------------------------

-- super_admin(10)：目录 + 三个子页
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at)
VALUES (10, 249, NOW()), (10, 250, NOW()), (10, 251, NOW()), (10, 252, NOW())
ON CONFLICT DO NOTHING;

-- kf_manager(11)：目录 + 三个子页
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at)
VALUES (11, 249, NOW()), (11, 250, NOW()), (11, 251, NOW()), (11, 252, NOW())
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 3. sys_role_permission — API 权限授权
-- -----------------------------------------------------------------------------
-- 三个统计 API 由 system:session:query（permission id=60）守护。
-- super_admin(10) 已持有该权限；kf_manager(11) 未持有，此处补授，
-- 否则 kf_manager 打开统计页调用 API 会返回 401/403。
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id)
VALUES (11, 60)
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 4. 更新序列
-- -----------------------------------------------------------------------------
SELECT pg_catalog.setval('cs_auth.sys_menu_id_seq', 252, true);
