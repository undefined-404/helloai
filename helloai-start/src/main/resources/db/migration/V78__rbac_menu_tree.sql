-- ============================================================
-- RBAC 菜单树 DB 化：sys_permission 扩展为菜单树事实源
-- 背景：G-012 落地时菜单结构仍在前端路由（MainLayout allMenus 硬编码），
--       权限码仅落 DB。本迁移补齐 parent_id/path/icon 三列 + 种子数据升级，
--       使「菜单树」与「权限码」同源（type=MENU 即菜单项），前端按接口渲染。
-- 影响：settings:view 由一级叶子升级为「系统设置」父节点；
--       新增 user:view / role:view / permission:view 三个子菜单权限码（仅 SUPER_ADMIN，
--       经 "*" 通配获得）；新增 deadletter:view 独立菜单（ADMIN 同步绑定）。
-- ============================================================

-- ------------------------------------------------------------
-- 1. 扩展列
-- ------------------------------------------------------------
ALTER TABLE sys_permission ADD COLUMN IF NOT EXISTS parent_id BIGINT;
ALTER TABLE sys_permission ADD COLUMN IF NOT EXISTS path VARCHAR(128);
ALTER TABLE sys_permission ADD COLUMN IF NOT EXISTS icon VARCHAR(64);

COMMENT ON COLUMN sys_permission.parent_id IS '父菜单 ID（NULL=一级菜单，type=MENU 时有效）';
COMMENT ON COLUMN sys_permission.path IS '前端路由路径（type=MENU 时有效；query 形式如 /sub-tasks?status=DEAD_LETTER）';
COMMENT ON COLUMN sys_permission.icon IS '前端菜单图标组件名（对齐 @element-plus/icons-vue）';

-- ------------------------------------------------------------
-- 2. 存量 17 个 MENU 种子补 path/icon（对齐原 MainLayout 菜单数据）
-- ------------------------------------------------------------
UPDATE sys_permission SET path = '/dashboard',       icon = 'Odometer'    WHERE code = 'dashboard:view';
UPDATE sys_permission SET path = '/requirement-chat', icon = 'ChatDotRound' WHERE code = 'task:create';
UPDATE sys_permission SET path = '/tasks',            icon = 'List'        WHERE code = 'task:view';
UPDATE sys_permission SET path = '/sub-tasks',        icon = 'Document'    WHERE code = 'subtask:view';
UPDATE sys_permission SET path = '/agents',           icon = 'User'        WHERE code = 'agent:view';
UPDATE sys_permission SET path = '/teams',            icon = 'UserFilled'  WHERE code = 'team:view';
UPDATE sys_permission SET path = '/browser-sessions', icon = 'Monitor'     WHERE code = 'browser:view';
UPDATE sys_permission SET path = '/inbox',            icon = 'Message'     WHERE code = 'inbox:view';
UPDATE sys_permission SET path = '/reviews',          icon = 'Select'      WHERE code = 'review:view';
UPDATE sys_permission SET path = '/event-stream',     icon = 'DataLine'    WHERE code = 'event:view';
UPDATE sys_permission SET path = '/quality-dashboard', icon = 'DataAnalysis' WHERE code = 'quality:view';
UPDATE sys_permission SET path = '/duty-leases',      icon = 'Clock'       WHERE code = 'duty:view';
UPDATE sys_permission SET path = '/attachments',      icon = 'Folder'      WHERE code = 'attachment:view';
UPDATE sys_permission SET path = '/settings',         icon = 'Tools'       WHERE code = 'settings:view';

-- ------------------------------------------------------------
-- 3. 新增 MENU 权限码：系统设置子菜单（parent=17 settings:view）+ 死信池
--    user:view / role:view / permission:view 仅 SUPER_ADMIN（"*" 通配），不绑 ADMIN；
--    deadletter:view 为普通菜单，ADMIN 可见。
-- ------------------------------------------------------------
INSERT INTO sys_permission (id, code, name, type, parent_id, path, icon, sort, create_by, update_by)
VALUES
    (24, 'user:view',         '用户管理', 'MENU', 17, '/system/users',       'User',    18, 'system', 'system'),
    (25, 'role:view',         '角色管理', 'MENU', 17, '/system/roles',       'Avatar',  19, 'system', 'system'),
    (26, 'permission:view',   '权限管理', 'MENU', 17, '/system/permissions', 'Key',     20, 'system', 'system'),
    (27, 'deadletter:view',   '死信池',   'MENU', NULL, '/sub-tasks?status=DEAD_LETTER', 'Warning', 5, 'system', 'system')
ON CONFLICT (id) DO NOTHING;

-- ------------------------------------------------------------
-- 4. ADMIN 绑定 deadletter:view（与 subtask:view 同级可见）
--    （user/role/permission:view 保持仅 SUPER_ADMIN，延续 V77「管理类仅超级管理员」口径）
-- ------------------------------------------------------------
INSERT INTO sys_role_permission (id, role_id, permission_id, create_by, update_by)
SELECT 9000000000000200001, 2, p.id, 'system', 'system'
FROM sys_permission p
WHERE p.code = 'deadletter:view'
ON CONFLICT (id) DO NOTHING;
