-- ============================================================
-- V3：测试用户种子数据
-- 三个账号用于多角色 UI 测试：
--   superadmin / Test@123456  → 超级管理员（全部菜单）
--   kfmanager  / Test@123456  → 客服管理员（智能客服全部，无系统管理）
--   kfstaff    / Test@123456  → 普通客服（仅对话）
--
-- BCrypt hash（Test@123456，cost=10）:
--   $2b$10$IbF1B4L.Tog2vsx7coxiTehgwstvHi96dSx.7GIgqTnD0yyGcPcO2
-- ============================================================

-- ============================================================
-- 1. 角色
-- ============================================================
INSERT INTO cs_auth.sys_role (id, role_key, role_name, is_system, status) VALUES
(10, 'super_admin', '超级管理员', TRUE,  'active'),
(11, 'kf_manager',  '客服管理员', FALSE, 'active'),
(12, 'kf_staff',    '普通客服',   FALSE, 'active')
ON CONFLICT (role_key) DO NOTHING;

-- ============================================================
-- 2. 角色数据权限范围
-- ============================================================
INSERT INTO cs_auth.sys_role_data_scope (role_id, scope_type) VALUES
(10, 'ALL'),
(11, 'DEPT_TREE'),
(12, 'SELF')
ON CONFLICT (role_id) DO UPDATE SET scope_type = EXCLUDED.scope_type;

-- ============================================================
-- 3. 角色菜单分配
-- ============================================================

-- 超级管理员：全部菜单
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id)
SELECT 10, id FROM cs_auth.sys_menu
ON CONFLICT DO NOTHING;

-- 客服管理员：智能客服全部（目录+菜单+按钮），无系统管理
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id) VALUES
(11, 100),(11, 101),(11, 102),(11, 103),
(11, 110),(11, 111),(11, 112),(11, 113),
(11, 120),(11, 121),(11, 122)
ON CONFLICT DO NOTHING;

-- 普通客服：仅智能客服目录 + 对话菜单
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id) VALUES
(12, 100),(12, 101)
ON CONFLICT DO NOTHING;

-- ============================================================
-- 4. 测试用户
-- ============================================================
INSERT INTO cs_auth.sys_user (id, username, display_name, email, password_hash, status, provider, must_change_password) VALUES
(1001, 'superadmin', '超级管理员', 'superadmin@test.com',
    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
    'active', 'LOCAL', FALSE),
(1002, 'kfmanager',  '客服管理员', 'kfmanager@test.com',
    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
    'active', 'LOCAL', FALSE),
(1003, 'kfstaff',    '普通客服',   'kfstaff@test.com',
    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
    'active', 'LOCAL', FALSE)
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- 5. 用户-角色关联
-- ============================================================
INSERT INTO cs_auth.sys_user_role (user_id, role_id) VALUES
(1001, 10),
(1002, 11),
(1003, 12)
ON CONFLICT DO NOTHING;
