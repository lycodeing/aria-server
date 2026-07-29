-- BUG-003 补丁：为 kfmanager 角色（role_id=11）补充 Dashboard 菜单权限
-- 菜单 1：概览目录（/dashboard）
-- 菜单 2：分析页（/dashboard/analysis）
-- 菜单 3：工作台（/dashboard/workspace）
-- 执行前检查：若已存在则跳过（INSERT ... ON CONFLICT DO NOTHING）
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at)
VALUES
    (11, 1, NOW()),
    (11, 2, NOW()),
    (11, 3, NOW())
ON CONFLICT DO NOTHING;
