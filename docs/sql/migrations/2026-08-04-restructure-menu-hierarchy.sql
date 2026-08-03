-- =============================================================================
-- 菜单布局重构：三目录 → 四目录（概览/会话管理/客服管理/系统管理）
-- 日期：2026-08-04
--
-- 背景：
-- 1. 原"智能客服"目录(100)混装了座席工作台、知识库、DIT配置、标签管理等不同职责页面，
--    重构为"客服管理"(100)仅保留管理类配置，新增"会话管理"(248)承载会话相关页面。
-- 2. 修复 bug：kf_staff 被授权的"对话"页(101) is_visible=false 导致从未生成路由，
--    而真正的座席工作台(103)未授权给 kf_staff → 授权 103 给 kf_staff，下线 101。
-- 3. SLA/Webhook/违规记录 从系统管理(200)移到会话管理(248)，它们衡量的是会话时效。
-- 4. 新增"我的数据"菜单(247)挂在概览(1)下，供普通客服查看个人工作量+满意度。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 新增菜单项
-- -----------------------------------------------------------------------------

-- [247] 我的数据（挂在概览 1 下）
INSERT INTO cs_auth.sys_menu
    (id, parent_id, menu_type, menu_name, menu_key, path, component, icon,
     sort_order, is_visible, is_cache, is_external, redirect, permission_key,
     status, remark, created_by, created_at, updated_at)
VALUES
    (247, 1, 'MENU', '我的数据', 'DashboardMyData', '/dashboard/my-data',
     'dashboard/my-data/index', 'lucide:user-cog', 3, true, true, false, NULL, NULL,
     'active', '普通客服个人数据页：工作量+满意度', NULL, NOW(), NOW())
ON CONFLICT (id) DO UPDATE SET
    parent_id = EXCLUDED.parent_id,
    menu_type = EXCLUDED.menu_type,
    menu_name = EXCLUDED.menu_name,
    menu_key  = EXCLUDED.menu_key,
    path      = EXCLUDED.path,
    component = EXCLUDED.component,
    icon      = EXCLUDED.icon,
    sort_order = EXCLUDED.sort_order,
    is_visible = EXCLUDED.is_visible,
    updated_at = NOW();

-- [248] 会话管理（顶级目录）
INSERT INTO cs_auth.sys_menu
    (id, parent_id, menu_type, menu_name, menu_key, path, component, icon,
     sort_order, is_visible, is_cache, is_external, redirect, permission_key,
     status, remark, created_by, created_at, updated_at)
VALUES
    (248, 0, 'DIRECTORY', '会话管理', 'Session', '/session',
     NULL, 'lucide:messages-square', 20, true, true, false, NULL, NULL,
     'active', '会话相关页面：座席工作台/会话查询/SLA管理', NULL, NOW(), NOW())
ON CONFLICT (id) DO UPDATE SET
    parent_id = EXCLUDED.parent_id,
    menu_type = EXCLUDED.menu_type,
    menu_name = EXCLUDED.menu_name,
    menu_key  = EXCLUDED.menu_key,
    path      = EXCLUDED.path,
    icon      = EXCLUDED.icon,
    sort_order = EXCLUDED.sort_order,
    is_visible = EXCLUDED.is_visible,
    updated_at = NOW();

-- -----------------------------------------------------------------------------
-- 2. 移动现有菜单到新目录（批量 UPDATE parent_id / path / component / sort_order）
-- -----------------------------------------------------------------------------

-- [103] 座席工作台 → 会话管理(248)下，路径改为 /session/agent
UPDATE cs_auth.sys_menu SET
    parent_id = 248,
    path = '/session/agent',
    component = 'session/agent/index',
    sort_order = 1,
    updated_at = NOW()
WHERE id = 103;

-- [209] 会话查询 → 会话管理(248)下，路径改为 /session/history
UPDATE cs_auth.sys_menu SET
    parent_id = 248,
    path = '/session/history',
    sort_order = 2,
    updated_at = NOW()
WHERE id = 209;

-- [206] SLA管理 → 会话管理(248)下，路径改为 /session/sla
UPDATE cs_auth.sys_menu SET
    parent_id = 248,
    path = '/session/sla',
    component = 'session/sla/index',
    sort_order = 3,
    updated_at = NOW()
WHERE id = 206;

-- [245] 通知配置 → 会话管理(248)下，路径改为 /session/webhooks
UPDATE cs_auth.sys_menu SET
    parent_id = 248,
    path = '/session/webhooks',
    component = 'session/sla/webhook',
    sort_order = 4,
    updated_at = NOW()
WHERE id = 245;

-- [246] SLA违规记录 → 会话管理(248)下，路径改为 /session/breaches
UPDATE cs_auth.sys_menu SET
    parent_id = 248,
    path = '/session/breaches',
    component = 'session/sla/breaches',
    sort_order = 5,
    updated_at = NOW()
WHERE id = 246;

-- [100] 智能客服 → 客服管理（重命名，调整排序）
UPDATE cs_auth.sys_menu SET
    menu_name = '客服管理',
    sort_order = 30,
    updated_at = NOW()
WHERE id = 100;

-- [208] 标签管理 → 客服管理(100)下，路径从 /system/tags 改为 /customerservice/tags
UPDATE cs_auth.sys_menu SET
    parent_id = 100,
    path = '/customerservice/tags',
    component = 'customerservice/tags/index',
    sort_order = 5,
    updated_at = NOW()
WHERE id = 208;

-- [101] 对话 → 下线（is_visible=false 保持，确保不生成路由）
UPDATE cs_auth.sys_menu SET
    is_visible = false,
    updated_at = NOW()
WHERE id = 101;

-- -----------------------------------------------------------------------------
-- 3. sys_role_menu — 补授权 / 移除
-- -----------------------------------------------------------------------------

-- 3.1 新增授权（幂等）
-- super_admin(10)：获得新增的 247 + 248
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at)
VALUES (10, 247, NOW()), (10, 248, NOW())
ON CONFLICT DO NOTHING;

-- kf_manager(11)：获得新增的 247 + 248
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at)
VALUES (11, 247, NOW()), (11, 248, NOW())
ON CONFLICT DO NOTHING;

-- kf_staff(12)：获得概览目录(1) + 我的数据(247) + 会话管理目录(248) + 座席工作台(103)
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at)
VALUES (12, 1, NOW()), (12, 247, NOW()), (12, 248, NOW()), (12, 103, NOW())
ON CONFLICT DO NOTHING;

-- 3.2 移除：从所有角色移除 101（对话页下线）
DELETE FROM cs_auth.sys_role_menu WHERE menu_id = 101;

-- -----------------------------------------------------------------------------
-- 4. 更新序列
-- -----------------------------------------------------------------------------
SELECT pg_catalog.setval('cs_auth.sys_menu_id_seq', 248, true);
