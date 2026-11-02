-- ============================================================
-- PA-4 个人工作台独立菜单入口（/workbench）
-- 背景：工作台四段（身份卡 / 最近任务 / 工作产物 / 项目历史）收进对话页右栏，
--       旧「对话新建」入口保持产物面板原逻辑（仅本会话），工作台另开一级菜单。
-- 影响：新增 MENU 权限码 workbench:view（id=133，接续 V130 的 132）；
--       component 指向极薄包装 workbench/Workbench（内部复用 RequirementChat）。
--       绑定 ADMIN(2) + NORMAL_USER(3)；GUEST 不绑（对齐 V89「游客不得创建对话」口径）。
-- ============================================================

-- ------------------------------------------------------------
-- 1) 菜单事实源：个人工作台（一级菜单，sort=1 紧随概览）
--    暂设 hidden=1：侧边栏不展示，路由仍注册可达；本机菜单由人工调整，
--    待页面布局重新敲定后再放开显示（不改版本号，直接调整本迁移）。
-- ------------------------------------------------------------
INSERT INTO sys_permission (id, code, name, type, parent_id, path, icon, component, sort, hidden, create_by, update_by)
VALUES (133, 'workbench:view', '个人工作台', 'MENU', NULL, '/workbench', 'Grid', 'workbench/Workbench', 1, 1, 'system', 'system')
ON CONFLICT (id) DO NOTHING;

-- ------------------------------------------------------------
-- 2) 角色绑定
-- ------------------------------------------------------------
INSERT INTO sys_role_permission (id, role_id, permission_id, create_by, update_by)
SELECT 9000000000000400000 + p.id, 2, p.id, 'system', 'system'
FROM sys_permission p
WHERE p.deleted = 0
  AND p.code = 'workbench:view'
ON CONFLICT DO NOTHING;

INSERT INTO sys_role_permission (id, role_id, permission_id, create_by, update_by)
SELECT 9000000000000500000 + p.id, 3, p.id, 'system', 'system'
FROM sys_permission p
WHERE p.deleted = 0
  AND p.code = 'workbench:view'
ON CONFLICT DO NOTHING;
