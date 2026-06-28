-- ============================================================
-- V2：菜单种子数据 + 权限种子数据
-- 智能客服模块菜单树 + 系统管理菜单 + 角色数据权限默认配置
-- ============================================================

-- ============================================================
-- 1. 菜单树（智能客服 + 系统管理）
-- ============================================================
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, permission_key) VALUES
-- 一级目录：智能客服
(100, 0,   'DIRECTORY', '智能客服',   'CustomerService',         '/customerservice',            NULL,                               'lucide:bot',              10, TRUE,  FALSE, NULL),
-- 二级菜单
(101, 100, 'MENU',      '对话',       'CustomerServiceChat',     '/customerservice/chat',       'customerservice/chat/index',       'lucide:message-circle',    1, TRUE,  TRUE,  NULL),
(102, 100, 'MENU',      '知识库',     'CustomerServiceKnowledge','/customerservice/knowledge',  'customerservice/knowledge/index',  'lucide:book-open',         2, TRUE,  TRUE,  NULL),
(103, 100, 'MENU',      '座席工作台', 'CustomerServiceAgent',    '/customerservice/agent',      'customerservice/agent/index',     'lucide:headphones',        3, TRUE,  TRUE,  NULL),
-- 知识库按钮权限
(110, 102, 'BUTTON', '上传文档', 'knowledge:doc:upload',   NULL, NULL, NULL, 1, FALSE, FALSE, 'knowledge:doc:upload'),
(111, 102, 'BUTTON', '审核文档', 'knowledge:doc:review',   NULL, NULL, NULL, 2, FALSE, FALSE, 'knowledge:doc:review'),
(112, 102, 'BUTTON', '下线文档', 'knowledge:doc:offline',  NULL, NULL, NULL, 3, FALSE, FALSE, 'knowledge:doc:offline'),
(113, 102, 'BUTTON', '删除文档', 'knowledge:doc:delete',   NULL, NULL, NULL, 4, FALSE, FALSE, 'knowledge:doc:delete'),
-- 座席工作台按钮权限
(120, 103, 'BUTTON', '接入会话', 'agent:session:accept',   NULL, NULL, NULL, 1, FALSE, FALSE, 'agent:session:accept'),
(121, 103, 'BUTTON', '结束会话', 'agent:session:close',    NULL, NULL, NULL, 2, FALSE, FALSE, 'agent:session:close'),
(122, 103, 'BUTTON', '转交会话', 'agent:session:transfer', NULL, NULL, NULL, 3, FALSE, FALSE, 'agent:session:transfer'),

-- 一级目录：系统管理
(200, 0,   'DIRECTORY', '系统管理', 'System',     '/system',       NULL,                  'lucide:settings',    90, TRUE, FALSE, NULL),
(201, 200, 'MENU',      '用户管理', 'SystemUser', '/system/user',  'system/user/index',   'lucide:users',        1, TRUE, TRUE,  NULL),
(202, 200, 'MENU',      '角色管理', 'SystemRole', '/system/role',  'system/role/index',   'lucide:shield',       2, TRUE, TRUE,  NULL),
(203, 200, 'MENU',      '菜单管理', 'SystemMenu', '/system/menu',  'system/menu/index',   'lucide:layout-list',  3, TRUE, TRUE,  NULL),
(204, 200, 'MENU',      '部门管理', 'SystemDept', '/system/dept',  'system/dept/index',   'lucide:building-2',   4, TRUE, TRUE,  NULL),
-- 用户管理按钮
(210, 201, 'BUTTON', '新增用户', 'system:user:create',      NULL, NULL, NULL, 1, FALSE, FALSE, 'system:user:create'),
(211, 201, 'BUTTON', '编辑用户', 'system:user:update',      NULL, NULL, NULL, 2, FALSE, FALSE, 'system:user:update'),
(212, 201, 'BUTTON', '删除用户', 'system:user:delete',      NULL, NULL, NULL, 3, FALSE, FALSE, 'system:user:delete'),
(213, 201, 'BUTTON', '重置密码', 'system:user:reset-pwd',   NULL, NULL, NULL, 4, FALSE, FALSE, 'system:user:reset-pwd'),
(214, 201, 'BUTTON', '分配角色', 'system:user:assign-role', NULL, NULL, NULL, 5, FALSE, FALSE, 'system:user:assign-role'),
-- 角色管理按钮
(220, 202, 'BUTTON', '新增角色', 'system:role:create',      NULL, NULL, NULL, 1, FALSE, FALSE, 'system:role:create'),
(221, 202, 'BUTTON', '编辑角色', 'system:role:update',      NULL, NULL, NULL, 2, FALSE, FALSE, 'system:role:update'),
(222, 202, 'BUTTON', '删除角色', 'system:role:delete',      NULL, NULL, NULL, 3, FALSE, FALSE, 'system:role:delete'),
(223, 202, 'BUTTON', '分配菜单', 'system:role:assign-menu', NULL, NULL, NULL, 4, FALSE, FALSE, 'system:role:assign-menu')
ON CONFLICT (menu_key) DO NOTHING;

-- ============================================================
-- 2. 接口/按钮级权限定义
-- ============================================================
INSERT INTO cs_auth.sys_permission (permission_key, permission_name, module) VALUES
('knowledge:doc:upload',    '上传文档',   'knowledge'),
('knowledge:doc:review',    '审核文档',   'knowledge'),
('knowledge:doc:offline',   '下线文档',   'knowledge'),
('knowledge:doc:delete',    '删除文档',   'knowledge'),
('agent:session:accept',    '接入会话',   'agent'),
('agent:session:close',     '结束会话',   'agent'),
('agent:session:transfer',  '转交会话',   'agent'),
('system:user:create',      '新增用户',   'system'),
('system:user:update',      '编辑用户',   'system'),
('system:user:delete',      '删除用户',   'system'),
('system:user:reset-pwd',   '重置密码',   'system'),
('system:user:assign-role', '分配角色',   'system'),
('system:role:create',      '新增角色',   'system'),
('system:role:update',      '编辑角色',   'system'),
('system:role:delete',      '删除角色',   'system'),
('system:role:assign-menu', '分配菜单',   'system')
ON CONFLICT (permission_key) DO NOTHING;
