-- ============================================================
-- RBAC 基础架构深化（BASE-1.1）：菜单组件列 + 动作级权限码
-- 背景：参考 JeecgBoot 标准化思路，对齐基础架构差距——
--       ① sys_permission 缺 component（菜单组件路径），前端无法动态生成路由；
--       ② 权限码为资源级（user:manage / role:manage），未到动作级
--          （user:add / user:edit / ... ），按钮级权限无法落地。
-- 本迁移只加列 + 种子，行为变化在应用层（BASE-1.2~1.6 / 2.1~2.3）。
-- 影响：V77 / V78 只读，本迁移 V79 递增；新增动作码仅 SUPER_ADMIN（"*"）生效，
--       延续「管理类动作仅超级管理员」口径，不绑 ADMIN。
-- ============================================================

-- ------------------------------------------------------------
-- 1. 扩展列：component（菜单组件路径，驱动前端动态路由懒加载）
-- ------------------------------------------------------------
ALTER TABLE sys_permission ADD COLUMN IF NOT EXISTS component VARCHAR(128);

COMMENT ON COLUMN sys_permission.component IS '前端组件路径（相对 src/views，不含扩展名；type=MENU 时有效，父节点为 NULL）';

-- ------------------------------------------------------------
-- 2. 存量 MENU 种子补 component（对齐前端 views 结构与 router 路径）
-- ------------------------------------------------------------
UPDATE sys_permission SET component = 'Dashboard'                  WHERE code = 'dashboard:view';
UPDATE sys_permission SET component = 'task/TaskList'              WHERE code = 'task:view';
UPDATE sys_permission SET component = 'requirement/RequirementChat' WHERE code = 'task:create';
UPDATE sys_permission SET component = 'subtask/SubTaskList'        WHERE code = 'subtask:view';
UPDATE sys_permission SET component = 'agent/AgentList'            WHERE code = 'agent:view';
UPDATE sys_permission SET component = 'team/TeamList'              WHERE code = 'team:view';
UPDATE sys_permission SET component = 'browser/BrowserSessionList' WHERE code = 'browser:view';
UPDATE sys_permission SET component = 'review/ReviewList'          WHERE code = 'review:view';
UPDATE sys_permission SET component = 'event/EventStreamWorkbench' WHERE code = 'event:view';
UPDATE sys_permission SET component = 'quality/QualityDashboard'   WHERE code = 'quality:view';
UPDATE sys_permission SET component = 'reward/RewardList'          WHERE code = 'reward:view';
UPDATE sys_permission SET component = 'activity/ActivityList'      WHERE code = 'activity:view';
UPDATE sys_permission SET component = 'rule/RuleList'              WHERE code = 'rule:view';
UPDATE sys_permission SET component = 'duty/DutyLeaseList'         WHERE code = 'duty:view';
UPDATE sys_permission SET component = 'inbox/AgentInbox'           WHERE code = 'inbox:view';
UPDATE sys_permission SET component = 'attachment/AttachmentList'  WHERE code = 'attachment:view';
UPDATE sys_permission SET component = 'system/UserList'            WHERE code = 'user:view';
UPDATE sys_permission SET component = 'system/RoleList'            WHERE code = 'role:view';
UPDATE sys_permission SET component = 'system/PermissionList'      WHERE code = 'permission:view';
UPDATE sys_permission SET component = 'subtask/SubTaskList'        WHERE code = 'deadletter:view';
-- settings:view 为聚合父节点（无独立组件），component 保持 NULL

-- ------------------------------------------------------------
-- 3. 新增动作级权限码（type=API，供 @SaCheckPermission 方法级鉴权 + 按钮级 v-auth）
--    仅 SUPER_ADMIN（"*" 通配）默认获得；ADMIN 不绑（延续 V77 管理类仅超级管理员口径）。
-- ------------------------------------------------------------
INSERT INTO sys_permission (id, code, name, type, sort, create_by, update_by)
VALUES
    (28, 'user:add',         '用户-新增',     'API', 24, 'system', 'system'),
    (29, 'user:edit',        '用户-编辑',     'API', 25, 'system', 'system'),
    (30, 'user:delete',      '用户-删除',     'API', 26, 'system', 'system'),
    (31, 'user:assign-role', '用户-分配角色', 'API', 27, 'system', 'system'),
    (32, 'user:reset-pwd',   '用户-重置密码', 'API', 28, 'system', 'system'),
    (33, 'role:add',         '角色-新增',     'API', 29, 'system', 'system'),
    (34, 'role:edit',        '角色-编辑',     'API', 30, 'system', 'system'),
    (35, 'role:delete',      '角色-删除',     'API', 31, 'system', 'system'),
    (36, 'role:assign-perm', '角色-分配权限', 'API', 32, 'system', 'system'),
    (37, 'permission:add',   '权限/菜单-新增', 'API', 33, 'system', 'system'),
    (38, 'permission:edit',  '权限/菜单-编辑', 'API', 34, 'system', 'system'),
    (39, 'permission:delete','权限/菜单-删除', 'API', 35, 'system', 'system')
ON CONFLICT (id) DO NOTHING;
