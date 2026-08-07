-- =============================================================================
-- 权限补丁：补齐 RoleController / MenuController 缺失的权限 key
-- 日期：2026-08-06
-- 分支：fix/code-review-remediation
--
-- 背景（代码评审 P0 修复）：
--   RoleController / MenuController 此前仅有类级 @SaCheckLogin，所有写接口无权限
--   校验，任意登录用户可自我提权（assignPermissions）、篡改全局菜单树，构成垂直
--   越权。本次给这些接口补 @SaCheckPermission，需同步补齐对应权限 key 并绑给
--   super_admin，否则包括 super_admin 在内的所有人都会 403。
--
-- 涉及 Controller 与新增/复用的权限 key：
--   RoleController.create            → system:role:create      (已存在 id=13)
--   RoleController.update            → system:role:update      (已存在 id=14)
--   RoleController.delete            → system:role:delete      (已存在 id=15)
--   RoleController.assignMenus       → system:role:assign-menu (已存在 id=16)
--   RoleController.assignPermissions → system:role:assign-perm (新增 id=65)
--   RoleController.setDataScope      → system:role:data-scope  (新增 id=66)
--   MenuController.create            → system:menu:create      (新增 id=67)
--   MenuController.update            → system:menu:update      (新增 id=68)
--   MenuController.delete            → system:menu:delete      (新增 id=69)
--
-- 说明：只读接口（list/getById/permissionTree/getRoleMenus/getDataScope/allMenus）
--       仍仅要求登录（@SaCheckLogin），不额外新增查询权限，保持与现有 role/user
--       管理页读接口一致（避免 super_admin 之外的角色打开管理页即空白）。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. sys_permission — 新增缺失权限 key
-- -----------------------------------------------------------------------------
INSERT INTO cs_auth.sys_permission
    (id, permission_key, permission_name, module, description, created_at)
VALUES
    (65, 'system:role:assign-perm', '角色-分配权限', 'system', '给角色分配接口权限（高危，可提权）', NOW()),
    (66, 'system:role:data-scope',  '角色-数据范围', 'system', '设置角色数据权限范围',               NOW()),
    (67, 'system:menu:create',      '菜单-新增',     'system', '新增菜单或按钮',                     NOW()),
    (68, 'system:menu:update',      '菜单-编辑',     'system', '编辑菜单或按钮',                     NOW()),
    (69, 'system:menu:delete',      '菜单-删除',     'system', '删除菜单或按钮',                     NOW())
ON CONFLICT (id) DO NOTHING;

-- -----------------------------------------------------------------------------
-- 2. sys_menu — 新增功能按钮（BUTTON 类型），挂在对应管理页下
--    角色管理页 id=202，菜单管理页 id=203
-- -----------------------------------------------------------------------------
INSERT INTO cs_auth.sys_menu
    (id, parent_id, menu_type, menu_name, menu_key, path, component, icon,
     sort_order, is_visible, is_cache, is_external, redirect, permission_key,
     status, remark, created_by, created_at, updated_at)
VALUES
    (224, 202, 'BUTTON', '分配权限', 'system:role:assign-perm', NULL, NULL, NULL, 5, false, false, false, NULL, 'system:role:assign-perm', 'active', NULL, NULL, NOW(), NOW()),
    (225, 202, 'BUTTON', '数据范围', 'system:role:data-scope',  NULL, NULL, NULL, 6, false, false, false, NULL, 'system:role:data-scope',  'active', NULL, NULL, NOW(), NOW()),
    (250, 203, 'BUTTON', '新增菜单', 'system:menu:create',      NULL, NULL, NULL, 1, false, false, false, NULL, 'system:menu:create',      'active', NULL, NULL, NOW(), NOW()),
    (251, 203, 'BUTTON', '编辑菜单', 'system:menu:update',      NULL, NULL, NULL, 2, false, false, false, NULL, 'system:menu:update',      'active', NULL, NULL, NOW(), NOW()),
    (252, 203, 'BUTTON', '删除菜单', 'system:menu:delete',      NULL, NULL, NULL, 3, false, false, false, NULL, 'system:menu:delete',      'active', NULL, NULL, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- -----------------------------------------------------------------------------
-- 3. sys_role_permission — 绑定给 super_admin(10)
--    这些是系统级高危操作，仅 super_admin 拥有。
-- -----------------------------------------------------------------------------
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id)
VALUES
    (10, 65), (10, 66), (10, 67), (10, 68), (10, 69)
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 4. sys_role_menu — 按钮可见性绑定给 super_admin(10)
-- -----------------------------------------------------------------------------
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at)
VALUES
    (10, 224, NOW()), (10, 225, NOW()),
    (10, 250, NOW()), (10, 251, NOW()), (10, 252, NOW())
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 5. 更新序列，防止后续手动插入 id 冲突
-- -----------------------------------------------------------------------------
SELECT pg_catalog.setval('cs_auth.sys_permission_id_seq', 69, true);
SELECT pg_catalog.setval('cs_auth.sys_menu_id_seq',      252, true);
