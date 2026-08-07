-- =============================================================================
-- 权限补丁：补齐 DIT / 快捷回复 / Dashboard / 知识库文档只读 缺失的权限 key
-- 日期：2026-08-06
-- 分支：fix/code-review-remediation
--
-- 背景（代码评审 P0 修复）：
--   以下 Controller 此前仅有类级/方法级 @SaCheckLogin，只校验登录、不校验权限，
--   任意登录用户即可读写 DIT 配置、快捷回复、查看全局经营数据、管理知识库文档，
--   构成水平/垂直越权。本次给这些接口补 @SaCheckPermission，需同步补齐对应权限
--   key 并绑给 super_admin，否则包括 super_admin 在内的所有人都会 403。
--
-- 涉及 Controller 与新增/复用的权限 key：
--   DitToolController        list                          → system:dit:view      (新增 id=70)
--                            create/update/delete/test     → system:dit:manage    (新增 id=71)
--   DitDomainController      list                          → system:dit:view
--                            create/update/delete          → system:dit:manage
--   DitIntentController      list*（intents/slots/bindings）→ system:dit:view
--                            create/update/delete*         → system:dit:manage
--   CannedResponseAdminCtrl  listGroups/listPublic         → system:canned:view   (新增 id=72)
--                            create/update/delete*          → system:canned:manage (新增 id=73)
--   DashboardController      所有非 /my-* 只读接口          → system:dashboard:view(新增 id=74)
--                            /my-* 个人数据接口保持仅登录（不新增权限）
--   KnowledgeDocController   list/status/preview/chunks/    → knowledge:doc:view   (新增 id=75)
--                            stats/kb-stats/search-test
--                            upload/retry/reingest          → knowledge:doc:upload (已存在 id=1)
--                            review                         → knowledge:doc:review (已存在 id=2)
--                            offline/batch-offline          → knowledge:doc:offline(已存在 id=3)
--   KnowledgeChunkController disable/enable/updateContent/  → knowledge:doc:review (已存在 id=2，复用审核权限)
--                            addQA
--
-- 说明：本补丁仅新增接口级权限（sys_permission）并绑定给 super_admin，
--       不新增 sys_menu。这些均为接口级权限，前端菜单/按钮的可见性后续单独处理。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. sys_permission — 新增缺失权限 key
--    module：dit/canned/dashboard 归 'system'，knowledge:doc:view 归 'knowledge'
-- -----------------------------------------------------------------------------
INSERT INTO cs_auth.sys_permission
    (id, permission_key, permission_name, module, description, created_at)
VALUES
    (70, 'system:dit:view',        'DIT-查看',        'system',    '查看 DIT 工具/领域/意图/槽位/绑定配置', NOW()),
    (71, 'system:dit:manage',      'DIT-管理',        'system',    '新增/编辑/删除/测试 DIT 配置',          NOW()),
    (72, 'system:canned:view',     '快捷回复-查看',   'system',    '查看快捷回复分组与公共快捷回复',        NOW()),
    (73, 'system:canned:manage',   '快捷回复-管理',   'system',    '新增/编辑/删除快捷回复分组与内容',      NOW()),
    (74, 'system:dashboard:view',  '经营看板-查看',   'system',    '查看全局经营看板统计数据',              NOW()),
    (75, 'knowledge:doc:view',     '知识文档-查看',   'knowledge', '查看知识库文档列表/详情/统计/检索测试',  NOW())
ON CONFLICT (id) DO NOTHING;

-- -----------------------------------------------------------------------------
-- 2. sys_role_permission — 绑定给 super_admin(10)
--    super_admin 无通配权限，必须逐条绑定，否则超管也会 403。
-- -----------------------------------------------------------------------------
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id)
VALUES
    (10, 70), (10, 71), (10, 72), (10, 73), (10, 74), (10, 75)
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 3. 更新序列，防止后续手动插入 id 冲突
-- -----------------------------------------------------------------------------
SELECT pg_catalog.setval('cs_auth.sys_permission_id_seq', 75, true);
