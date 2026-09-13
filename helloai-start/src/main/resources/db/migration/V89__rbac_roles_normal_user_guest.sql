-- ============================================================
-- BASE-4.3 角色体系扩充：NORMAL_USER / GUEST + 权限绑定
--
-- 背景：原内置角色仅 SUPER_ADMIN（"*" 通配）/ ADMIN 两个；平台公网发布需要
--       「可浏览系统功能、不可改数据」的只读演示账户，以及「业务操作者」角色。
--
-- 角色分层（见《HelloAI 目标架构》§12.2）：
--   SUPER_ADMIN  通配 "*"：平台全能力（无需绑定，由 StpInterface 特判）
--   ADMIN        管理面运维 + 业务读写（平台配置类专有码不授予）
--   NORMAL_USER  业务读写，无系统设置与平台配置（不绑 settings:/user:/role:/permission:/menu: 码）
--   GUEST        纯只读：仅 *:view 菜单码，零写动作码（写接口一律 403）
--
-- 与本批 V88 的关系：V88 先建权限码事实源，V89 再做角色绑定（顺序不可颠倒）。
-- 红线：V1~V88 只读；仅新增角色与关联行；不新增第二套权限体系。
-- 回滚：按 code 反向删角色/关联行即可（无 DDL）。
-- ============================================================

-- ------------------------------------------------------------
-- 1) 新增内置角色（id 3 / 4；1=SUPER_ADMIN、2=ADMIN 已存在）
-- ------------------------------------------------------------
INSERT INTO sys_role (id, code, name, description, status, sort, create_by, update_by, remark)
VALUES
    (3, 'NORMAL_USER', '普通用户', '业务操作者：业务读写，无系统设置与平台配置',
     'ACTIVE', 3, 'system', 'system', '内置角色'),
    (4, 'GUEST',       '游客',     '只读演示：仅可浏览业务数据，全部写接口拒绝',
     'ACTIVE', 4, 'system', 'system', '内置角色')
ON CONFLICT DO NOTHING;

-- ------------------------------------------------------------
-- 2) ADMIN（role_id=2）绑定：运维管理面动作码 + 业务写码
--    不授予平台配置类（llm-provider:* / config:* / prompt-template:* /
--    platform-provider:* / mq-recovery:*）与 RBAC 管理类（user/role/permission/menu:add|edit|delete）
--    —— 延续 V77/V78「管理类仅超管」口径。
-- ------------------------------------------------------------
INSERT INTO sys_role_permission (id, role_id, permission_id, create_by, update_by)
SELECT 9000000000000400000 + p.id, 2, p.id, 'system', 'system'
FROM sys_permission p
WHERE p.deleted = 0
  AND p.code IN (
    -- 运维管理面（Agent / Team / 工作流 / 质量）
    'agent:add', 'agent:edit', 'agent:delete', 'agent:key',
    'team:add', 'team:edit', 'team:archive', 'team:member',
    'workflow-template:view', 'workflow-template:add', 'workflow-template:edit',
    'workflow-template:publish', 'workflow-template:archive',
    'workflow-instance:view', 'workflow-instance:add',
    'quality:rebuild', 'quality:dispatch',
    -- 业务面写码：任务
    'task:add', 'task:edit', 'task:delete', 'task:plan', 'task:republish',
    'task:confirm-plan', 'task:reject-plan', 'task:report',
    -- 业务面写码：子任务
    'subtask:add', 'subtask:edit', 'subtask:claim', 'subtask:execute', 'subtask:submit',
    'subtask:complete', 'subtask:rework', 'subtask:block', 'subtask:reassign',
    'subtask:redispatch', 'subtask:pause', 'subtask:resume', 'subtask:status',
    -- 业务面写码：需求对话
    'conversation:add', 'conversation:send', 'conversation:finalize', 'conversation:regenerate',
    'conversation:abandon', 'conversation:delete', 'conversation:retry', 'conversation:mode',
    -- 业务面写码：审查 / 积分 / 模块 / 提示词增强 / 凭证 / 执行预览
    'review:add', 'score:adjust', 'module:edit', 'prompt:enhance',
    'credential:view', 'credential:bind', 'credential:rotate', 'credential:revoke',
    'agent-execution:preview'
  )
ON CONFLICT DO NOTHING;

-- ------------------------------------------------------------
-- 3) NORMAL_USER（role_id=3）绑定：业务菜单码 + 业务写码
--    不含 settings:view 及其子菜单（/settings 子树整体不可见）；
--    不含管理面动作码与凭证/执行预览等运维语义码。
-- ------------------------------------------------------------
INSERT INTO sys_role_permission (id, role_id, permission_id, create_by, update_by)
SELECT 9000000000000500000 + p.id, 3, p.id, 'system', 'system'
FROM sys_permission p
WHERE p.deleted = 0
  AND p.code IN (
    -- 菜单（业务侧，不含设置类）
    'dashboard:view', 'task:view', 'task:create', 'subtask:view', 'agent:view',
    'team:view', 'browser:view', 'review:view', 'event:view', 'quality:view',
    'reward:view', 'activity:view', 'rule:view', 'duty:view', 'inbox:view',
    'attachment:view', 'deadletter:view',
    -- 业务面写码
    'task:add', 'task:edit', 'task:delete', 'task:plan', 'task:republish',
    'task:confirm-plan', 'task:reject-plan', 'task:report',
    'subtask:add', 'subtask:edit', 'subtask:claim', 'subtask:execute', 'subtask:submit',
    'subtask:complete', 'subtask:rework', 'subtask:block', 'subtask:reassign',
    'subtask:redispatch', 'subtask:pause', 'subtask:resume', 'subtask:status',
    'conversation:add', 'conversation:send', 'conversation:finalize', 'conversation:regenerate',
    'conversation:abandon', 'conversation:delete', 'conversation:retry', 'conversation:mode',
    'review:add', 'score:adjust', 'module:edit', 'prompt:enhance'
  )
ON CONFLICT DO NOTHING;

-- ------------------------------------------------------------
-- 4) GUEST（role_id=4）绑定：仅只读菜单码，零写动作码
--    排除 task:create（对话新建入口）与 settings:view（系统设置子树）——
--    游客不得创建对话、不得进入设置类页面；全部写接口因无动作码而 403。
-- ------------------------------------------------------------
INSERT INTO sys_role_permission (id, role_id, permission_id, create_by, update_by)
SELECT 9000000000000600000 + p.id, 4, p.id, 'system', 'system'
FROM sys_permission p
WHERE p.deleted = 0
  AND p.type = 'MENU'
  AND p.code IN (
    'dashboard:view', 'task:view', 'subtask:view', 'agent:view', 'team:view',
    'browser:view', 'review:view', 'event:view', 'quality:view', 'reward:view',
    'activity:view', 'rule:view', 'duty:view', 'inbox:view', 'attachment:view',
    'deadletter:view'
  )
ON CONFLICT DO NOTHING;
