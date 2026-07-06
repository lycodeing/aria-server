-- ============================================================
-- DIT 框架管理菜单及权限迁移
-- migration-003-dit-menus.sql
-- 前提：init-all.sql 已执行（cs_auth schema、sys_menu、sys_role_menu 表已存在）
-- ============================================================

-- DIT 配置目录（智能客服子目录，parent_id=100，id=104）
INSERT INTO cs_auth.sys_menu
    (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, status)
VALUES
-- 目录节点
(104, 100, 'DIRECTORY', 'DIT配置', 'CustomerServiceDIT',
    '/customerservice/dit', NULL, 'lucide:settings-2', 40, TRUE, 'active'),

-- 页面节点
(105, 104, 'MENU', '领域与意图', 'CustomerServiceDITDomains',
    '/customerservice/dit/domains', 'customerservice/dit/domains/index',
    'lucide:layers', 10, TRUE, 'active'),
(106, 104, 'MENU', '工具注册中心', 'CustomerServiceDITTools',
    '/customerservice/dit/tools', 'customerservice/dit/tools/index',
    'lucide:wrench', 20, TRUE, 'active'),

-- 按钮权限（领域管理）
(130, 105, 'BUTTON', '新建领域', 'dit:domain:create',
    NULL, NULL, NULL, 0, TRUE, 'active'),
(131, 105, 'BUTTON', '编辑领域', 'dit:domain:update',
    NULL, NULL, NULL, 0, TRUE, 'active'),
(132, 105, 'BUTTON', '删除领域', 'dit:domain:delete',
    NULL, NULL, NULL, 0, TRUE, 'active'),
(133, 105, 'BUTTON', '管理意图', 'dit:intent:manage',
    NULL, NULL, NULL, 0, TRUE, 'active'),
(134, 105, 'BUTTON', '管理槽位', 'dit:slot:manage',
    NULL, NULL, NULL, 0, TRUE, 'active'),

-- 按钮权限（工具管理）
(135, 106, 'BUTTON', '注册工具', 'dit:tool:create',
    NULL, NULL, NULL, 0, TRUE, 'active'),
(136, 106, 'BUTTON', '编辑工具', 'dit:tool:update',
    NULL, NULL, NULL, 0, TRUE, 'active'),
(137, 106, 'BUTTON', '删除工具', 'dit:tool:delete',
    NULL, NULL, NULL, 0, TRUE, 'active'),
(138, 106, 'BUTTON', '测试工具', 'dit:tool:test',
    NULL, NULL, NULL, 0, TRUE, 'active');

-- ---- 角色权限绑定 ----

-- super_admin（id=10）：拥有全部 DIT 菜单权限
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id) VALUES
(10, 104), (10, 105), (10, 106),
(10, 130), (10, 131), (10, 132), (10, 133), (10, 134),
(10, 135), (10, 136), (10, 137), (10, 138);

-- kf_manager（id=11）：可管理意图/槽位/测试工具，不可新建/删除领域和工具
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id) VALUES
(11, 104), (11, 105), (11, 106),
(11, 133), (11, 134),  -- 管理意图/槽位
(11, 138);             -- 测试工具
