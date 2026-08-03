-- =============================================================================
-- 修复：Webhook 配置 / SLA 违规记录菜单缺失迁移记录 + 路由重定向死锁
-- 日期：2026-08-03
--
-- 背景：
-- 1. 245（通知配置/Webhook 配置）、246（SLA 违规记录）两条 sys_menu 记录此前只在
--    运行时数据库里手动创建，从未进入任何 SQL 迁移文件；patch-sla-biz-hours-
--    tags-permissions.sql 只建到 id=244。重建数据库会导致这两个菜单整体丢失。
-- 2. 246 此前 parent_id=206（挂在"SLA 管理"下作为子菜单）。vben-admin 的路由生成
--    逻辑（accessible.ts generateRoutes）对"有子节点且无显式 redirect"的节点会
--    自动注入 redirect 指向第一个子节点，导致点击"SLA 管理"（/system/sla）被
--    自动重定向到 246（/system/sla/breaches），"SLA 策略"列表页永远无法访问。
--    修复：246 提升为 200（系统管理）下的平级菜单，206 不再有 MENU 类型子节点。
-- 3. 246 的 icon 字段此前为空，导致"SLA 违规记录"菜单项不显示图标，一并补上。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. sys_menu — 补充 245（通知配置）+ 246（SLA 违规记录，parent_id 修正为 200）
-- -----------------------------------------------------------------------------
INSERT INTO cs_auth.sys_menu
    (id, parent_id, menu_type, menu_name, menu_key, path, component, icon,
     sort_order, is_visible, is_cache, is_external, redirect, permission_key,
     status, remark, created_by, created_at, updated_at)
VALUES
    -- 通知配置 / Webhook 配置（挂在系统管理 200 下，与 SLA 管理平级，非其子菜单）
    (245, 200, 'MENU', '通知配置', 'SystemSlaWebhooks', '/system/sla/webhooks',
     'system/sla/webhook', 'lucide:at-sign', 7, true, true, false, NULL, NULL,
     'active', 'Webhook 事件通知配置，通用事件范围（SLA/会话/评价），非 SLA 专属', NULL, NOW(), NOW()),
    -- SLA 违规记录（挂在系统管理 200 下，与 SLA 管理平级，避免 206 出现子节点触发自动 redirect）
    (246, 200, 'MENU', 'SLA 违规记录', 'SystemSlaBreaches', '/system/sla/breaches',
     'system/sla/breaches', 'lucide:alert-triangle', 8, true, true, false, NULL, NULL,
     'active', NULL, NULL, NOW(), NOW())
ON CONFLICT (id) DO UPDATE SET
    parent_id  = EXCLUDED.parent_id,
    menu_name  = EXCLUDED.menu_name,
    path       = EXCLUDED.path,
    component  = EXCLUDED.component,
    icon       = EXCLUDED.icon,
    sort_order = EXCLUDED.sort_order,
    is_visible = EXCLUDED.is_visible,
    updated_at = NOW();

-- 206（SLA 管理）sort_order 与 205（AI 模型配置）曾冲突，一并订正
UPDATE cs_auth.sys_menu SET sort_order = 6, updated_at = NOW() WHERE id = 206 AND sort_order <> 6;

-- -----------------------------------------------------------------------------
-- 2. sys_role_menu — 补充 245/246 的角色可见性（幂等）
-- -----------------------------------------------------------------------------
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at)
VALUES
    -- super_admin：全部可见
    (10, 245, NOW()), (10, 246, NOW()),
    -- kf_manager：全部可见（与 206 SLA 管理一致的可见范围）
    (11, 245, NOW()), (11, 246, NOW())
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 3. 更新序列（防止后续手动插入 id 冲突）
-- -----------------------------------------------------------------------------
SELECT pg_catalog.setval('cs_auth.sys_menu_id_seq', 246, true);
