-- =============================================================================
-- 权限补丁：SLA / 营业时间 / 标签 / 会话备注相关权限
-- 分支：feat/sla-biz-hours-tags
-- 日期：2026-07-24
--
-- 背景：以下 @SaCheckPermission 注解的权限 key 在 sys_permission 中缺失，
--       导致所有非 super_admin 角色调用对应接口时 403。
--
-- 涉及 Controller：
--   VisitorTagController   → session:tag:write
--   SessionTagController   → session:tag:write
--   SessionNoteController  → session:note:write
--   AdminSessionController → system:session:query
--   TagAdminController     → system:tag:manage
--   SlaController          → system:sla:manage / system:sla:view
--   WebhookController      → system:sla:manage
--   BusinessHoursController→ system:biz-hours:manage
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. sys_menu — 新增管理页菜单（MENU 类型）
-- -----------------------------------------------------------------------------
INSERT INTO cs_auth.sys_menu
    (id, parent_id, menu_type, menu_name, menu_key, path, component, icon,
     sort_order, is_visible, is_cache, is_external, redirect, permission_key,
     status, remark, created_by, created_at, updated_at)
VALUES
    -- SLA 管理页（挂在系统管理 200 下）
    (206, 200, 'MENU', 'SLA 管理',        'SystemSla',       '/system/sla',
     'system/sla/index',        'lucide:alarm-clock',     6,  true, true, false, NULL, NULL,
     'active', NULL, NULL, NOW(), NOW()),
    -- 营业时间管理页（挂在系统管理 200 下）
    (207, 200, 'MENU', '营业时间',        'SystemBizHours',  '/system/biz-hours',
     'system/biz-hours/index',  'lucide:clock',           7,  true, true, false, NULL, NULL,
     'active', NULL, NULL, NOW(), NOW()),
    -- 标签管理页（挂在系统管理 200 下）
    (208, 200, 'MENU', '标签管理',        'SystemTag',       '/system/tag',
     'system/tag/index',        'lucide:tag',             8,  true, true, false, NULL, NULL,
     'active', NULL, NULL, NOW(), NOW()),
    -- 会话查询管理页（挂在系统管理 200 下）
    (209, 200, 'MENU', '会话查询',        'SystemSession',   '/system/session',
     'system/session/index',    'lucide:message-square',  9,  true, true, false, NULL, NULL,
     'active', NULL, NULL, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- -----------------------------------------------------------------------------
-- 2. sys_menu — 新增功能按钮（BUTTON 类型）
-- -----------------------------------------------------------------------------
INSERT INTO cs_auth.sys_menu
    (id, parent_id, menu_type, menu_name, menu_key, path, component, icon,
     sort_order, is_visible, is_cache, is_external, redirect, permission_key,
     status, remark, created_by, created_at, updated_at)
VALUES
    -- 座席工作台（103）下的操作按钮
    (123, 103, 'BUTTON', '标签操作',   'session:tag:write',   NULL, NULL, NULL, 4, false, false, false, NULL, 'session:tag:write',   'active', NULL, NULL, NOW(), NOW()),
    (124, 103, 'BUTTON', '备注操作',   'session:note:write',  NULL, NULL, NULL, 5, false, false, false, NULL, 'session:note:write',  'active', NULL, NULL, NOW(), NOW()),
    -- SLA 管理（206）下的操作按钮
    (240, 206, 'BUTTON', 'SLA 管理',   'system:sla:manage',   NULL, NULL, NULL, 1, false, false, false, NULL, 'system:sla:manage',   'active', NULL, NULL, NOW(), NOW()),
    (241, 206, 'BUTTON', 'SLA 查看',   'system:sla:view',     NULL, NULL, NULL, 2, false, false, false, NULL, 'system:sla:view',     'active', NULL, NULL, NOW(), NOW()),
    -- 营业时间（207）下的操作按钮
    (242, 207, 'BUTTON', '营业时间管理','system:biz-hours:manage', NULL, NULL, NULL, 1, false, false, false, NULL, 'system:biz-hours:manage', 'active', NULL, NULL, NOW(), NOW()),
    -- 标签管理（208）下的操作按钮
    (243, 208, 'BUTTON', '标签管理',   'system:tag:manage',   NULL, NULL, NULL, 1, false, false, false, NULL, 'system:tag:manage',   'active', NULL, NULL, NOW(), NOW()),
    -- 会话查询（209）下的操作按钮
    (244, 209, 'BUTTON', '会话查询',   'system:session:query',NULL, NULL, NULL, 1, false, false, false, NULL, 'system:session:query','active', NULL, NULL, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- -----------------------------------------------------------------------------
-- 3. sys_permission — 补充缺失权限 key
-- -----------------------------------------------------------------------------
INSERT INTO cs_auth.sys_permission
    (id, permission_key, permission_name, module, description, created_at)
VALUES
    (58, 'session:tag:write',        '会话标签操作',   'agent',         '新增/删除会话标签及访客标签',       NOW()),
    (59, 'session:note:write',       '会话备注操作',   'agent',         '新增/编辑/删除会话内部备注',         NOW()),
    (60, 'system:session:query',     '会话管理-查询',  'system',        '管理后台查询/导出历史会话记录',       NOW()),
    (61, 'system:tag:manage',        '标签管理',       'system',        '创建/编辑/删除全局标签',             NOW()),
    (62, 'system:sla:manage',        'SLA 管理',       'system',        '创建/编辑/删除 SLA 策略及 Webhook', NOW()),
    (63, 'system:sla:view',          'SLA 查看',       'system',        '查看 SLA 策略列表及违规统计',        NOW()),
    (64, 'system:biz-hours:manage',  '营业时间管理',   'system',        '创建/编辑/删除营业时间规则',         NOW())
ON CONFLICT (id) DO NOTHING;

-- -----------------------------------------------------------------------------
-- 4. sys_role_permission — 角色权限分配
--
-- super_admin (10) : 全部新权限
-- kf_manager  (11) : 座席操作类 + 标签/SLA查看
-- kf_staff    (12) : 座席操作类（标签/备注）
-- -----------------------------------------------------------------------------
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id)
VALUES
    -- super_admin 获得全部新权限
    (10, 58), (10, 59), (10, 60), (10, 61), (10, 62), (10, 63), (10, 64),
    -- kf_manager：标签操作 / 备注操作 / 标签管理 / SLA查看
    (11, 58), (11, 59), (11, 61), (11, 63),
    -- kf_staff：标签操作 / 备注操作
    (12, 58), (12, 59)
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 5. sys_role_menu — 菜单可见性分配
-- -----------------------------------------------------------------------------
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at)
VALUES
    -- super_admin 获得全部新菜单和按钮
    (10, 206, NOW()), (10, 207, NOW()), (10, 208, NOW()), (10, 209, NOW()),
    (10, 123, NOW()), (10, 124, NOW()),
    (10, 240, NOW()), (10, 241, NOW()), (10, 242, NOW()),
    (10, 243, NOW()), (10, 244, NOW()),
    -- kf_manager：标签操作/备注操作按钮 + 标签管理页 + SLA查看
    (11, 123, NOW()), (11, 124, NOW()),
    (11, 208, NOW()), (11, 243, NOW()),
    (11, 206, NOW()), (11, 241, NOW()),
    -- kf_staff：标签操作/备注操作按钮
    (12, 123, NOW()), (12, 124, NOW())
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 6. 更新序列（防止后续手动插入 id 冲突）
-- -----------------------------------------------------------------------------
SELECT pg_catalog.setval('cs_auth.sys_menu_id_seq',       244, true);
SELECT pg_catalog.setval('cs_auth.sys_permission_id_seq',  64, true);
