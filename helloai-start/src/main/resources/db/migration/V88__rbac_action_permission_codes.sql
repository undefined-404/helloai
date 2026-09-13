-- ============================================================
-- BASE-4.4 全量接口动作级权限化：权限码扩充 + 粗粒度码退役
--
-- 背景（2026-09-13 代码核查）：
--   @SaCheckPermission 仅覆盖 4 个管理控制器 / 24 个方法，全仓 234 接口中
--   管理面 85 个无动作码、业务面 125 个全部无授权 → 任何「只读角色」都无法落地
--   （GUEST 登录后仍可 POST /api/tasks 建任务、POST /api/requirement-conversations 建对话）。
--
-- 本迁移只做「权限码事实源」扩充，不含角色绑定（角色在 V89）。
-- 命名规范：资源:动作（见《基础架构调整实施计划》§9.4.1）。
--
-- 边界口径（本批新增码的授权归属见 V89）：
--   · 平台配置类（llm-provider / config / prompt-template / platform-provider /
--     mq-recovery）归 SUPER_ADMIN 专有（延续 V77「管理类仅超管」口径）；
--   · 运维管理类（agent / team / workflow / quality）与业务面写码归 ADMIN + 按需 NORMAL_USER；
--   · GUEST 仅持 MENU 类 *:view 码，不绑任何写码。
--
-- 红线：V1~V87 只读，本迁移递增为 V88；不新增第二套权限体系（仍为 sys_permission 单事实源）。
-- ============================================================

-- ------------------------------------------------------------
-- 1) 管理面动作码（id 49~80）
--    读接口：仅平台配置类补 :view（用于把 ADMIN 挡在平台配置读之外）；
--            agent/team/workflow/dashboard/duty/browser 等运维读接口由
--            AdminOnlyInterceptor 的角色闸（SUPER_ADMIN|ADMIN）兜底，不补码。
-- ------------------------------------------------------------
INSERT INTO sys_permission (id, code, name, type, sort, create_by, update_by)
VALUES
    (49,  'agent:add',               'Agent-新增',            'API', 42, 'system', 'system'),
    (50,  'agent:edit',              'Agent-编辑',            'API', 43, 'system', 'system'),
    (51,  'agent:delete',            'Agent-删除',            'API', 44, 'system', 'system'),
    (52,  'agent:key',               'Agent-密钥重置',        'API', 45, 'system', 'system'),
    (53,  'llm-provider:view',       'LLM供应商-查看',        'API', 46, 'system', 'system'),
    (54,  'llm-provider:add',        'LLM供应商-新增',        'API', 47, 'system', 'system'),
    (55,  'llm-provider:edit',       'LLM供应商-编辑',        'API', 48, 'system', 'system'),
    (56,  'llm-provider:delete',     'LLM供应商-删除',        'API', 49, 'system', 'system'),
    (57,  'llm-provider:key',        'LLM供应商-密钥',        'API', 50, 'system', 'system'),
    (58,  'llm-provider:model',      'LLM供应商-模型配置',    'API', 51, 'system', 'system'),
    (59,  'team:add',                'Team-新增',             'API', 52, 'system', 'system'),
    (60,  'team:edit',               'Team-编辑',             'API', 53, 'system', 'system'),
    (61,  'team:archive',            'Team-归档',             'API', 54, 'system', 'system'),
    (62,  'team:member',             'Team-成员管理',         'API', 55, 'system', 'system'),
    (63,  'workflow-template:view',  '工作流模板-查看',       'API', 56, 'system', 'system'),
    (64,  'workflow-template:add',   '工作流模板-新增',       'API', 57, 'system', 'system'),
    (65,  'workflow-template:edit',  '工作流模板-编辑',       'API', 58, 'system', 'system'),
    (66,  'workflow-template:publish','工作流模板-发布',      'API', 59, 'system', 'system'),
    (67,  'workflow-template:archive','工作流模板-归档',      'API', 60, 'system', 'system'),
    (68,  'workflow-instance:view',  '工作流实例-查看',       'API', 61, 'system', 'system'),
    (69,  'prompt-template:view',    '提示词模板-查看',       'API', 62, 'system', 'system'),
    (70,  'prompt-template:add',     '提示词模板-新增',       'API', 63, 'system', 'system'),
    (71,  'prompt-template:edit',    '提示词模板-编辑',       'API', 64, 'system', 'system'),
    (72,  'prompt-template:delete',  '提示词模板-删除',       'API', 65, 'system', 'system'),
    (73,  'config:view',             '系统配置-查看',         'API', 66, 'system', 'system'),
    (74,  'config:edit',             '系统配置-修改',         'API', 67, 'system', 'system'),
    (75,  'platform-provider:view',  '平台供应商配置-查看',   'API', 68, 'system', 'system'),
    (76,  'platform-provider:edit',  '平台供应商配置-修改',   'API', 69, 'system', 'system'),
    (77,  'quality:rebuild',         '质量画像-重算',         'API', 70, 'system', 'system'),
    (78,  'quality:dispatch',        '质量-自动派发',         'API', 71, 'system', 'system'),
    (79,  'mq-recovery:view',        'MQ恢复-查看',           'API', 72, 'system', 'system'),
    (80,  'mq-recovery:replay',      'MQ恢复-重放',           'API', 73, 'system', 'system')
ON CONFLICT (id) DO NOTHING;

-- ------------------------------------------------------------
-- 2) 业务面动作码（id 81~118）
--    仅覆盖「写」接口（业务面读接口保持「登录即可读」，不补码）；
--    Agent / 系统身份通道（McpController / ArtifactUploadController / AgentInbox /
--    ActivityController#create / AgentController#register* / SetupController）不补码。
-- ------------------------------------------------------------
INSERT INTO sys_permission (id, code, name, type, sort, create_by, update_by)
VALUES
    (81,  'task:add',                 '任务-新建',            'API', 74, 'system', 'system'),
    (82,  'task:edit',                '任务-编辑',            'API', 75, 'system', 'system'),
    (83,  'task:delete',              '任务-删除',            'API', 76, 'system', 'system'),
    (84,  'task:plan',                '任务-拆解',            'API', 77, 'system', 'system'),
    (85,  'task:republish',           '任务-重新发布',        'API', 78, 'system', 'system'),
    (86,  'task:confirm-plan',        '任务-确认拆解',        'API', 79, 'system', 'system'),
    (87,  'task:reject-plan',         '任务-驳回拆解',        'API', 80, 'system', 'system'),
    (88,  'task:report',              '任务-终稿报告',        'API', 81, 'system', 'system'),
    (89,  'subtask:add',              '子任务-新建',          'API', 82, 'system', 'system'),
    (90,  'subtask:edit',             '子任务-编辑草稿',      'API', 83, 'system', 'system'),
    (91,  'subtask:claim',            '子任务-认领',          'API', 84, 'system', 'system'),
    (92,  'subtask:execute',          '子任务-执行',          'API', 85, 'system', 'system'),
    (93,  'subtask:submit',           '子任务-提交',          'API', 86, 'system', 'system'),
    (94,  'subtask:complete',         '子任务-完成',          'API', 87, 'system', 'system'),
    (95,  'subtask:rework',           '子任务-返工',          'API', 88, 'system', 'system'),
    (96,  'subtask:block',            '子任务-阻塞',          'API', 89, 'system', 'system'),
    (97,  'subtask:reassign',         '子任务-改派',          'API', 90, 'system', 'system'),
    (98,  'subtask:redispatch',       '子任务-重派',          'API', 91, 'system', 'system'),
    (99,  'subtask:pause',            '子任务-暂停',          'API', 92, 'system', 'system'),
    (100, 'subtask:resume',           '子任务-恢复',          'API', 93, 'system', 'system'),
    (101, 'subtask:status',           '子任务-状态变更',      'API', 94, 'system', 'system'),
    (102, 'conversation:add',         '需求对话-新建',        'API', 95, 'system', 'system'),
    (103, 'conversation:send',        '需求对话-发消息',      'API', 96, 'system', 'system'),
    (104, 'conversation:finalize',    '需求对话-生成终稿',    'API', 97, 'system', 'system'),
    (105, 'conversation:regenerate',  '需求对话-重新生成',    'API', 98, 'system', 'system'),
    (106, 'conversation:abandon',     '需求对话-放弃',        'API', 99, 'system', 'system'),
    (107, 'conversation:delete',      '需求对话-删除',        'API', 100, 'system', 'system'),
    (108, 'conversation:retry',       '需求对话-重试',        'API', 101, 'system', 'system'),
    (109, 'conversation:mode',        '需求对话-模式切换',    'API', 102, 'system', 'system'),
    (110, 'review:add',               '审查-提交',            'API', 103, 'system', 'system'),
    (111, 'score:adjust',             '积分-人工调整',        'API', 104, 'system', 'system'),
    (112, 'module:edit',              '模块配置-修改',        'API', 105, 'system', 'system'),
    (113, 'prompt:enhance',           '提示词-增强',          'API', 106, 'system', 'system'),
    (114, 'credential:view',          '凭证保险库-查看',      'API', 107, 'system', 'system'),
    (115, 'credential:bind',          '凭证保险库-绑定',      'API', 108, 'system', 'system'),
    (116, 'credential:rotate',        '凭证保险库-轮换',      'API', 109, 'system', 'system'),
    (117, 'credential:revoke',        '凭证保险库-吊销',      'API', 110, 'system', 'system'),
    (118, 'agent-execution:preview',  'Agent执行-连通性预览', 'API', 111, 'system', 'system'),
    (119, 'workflow-instance:add',    '工作流实例-创建',      'API', 112, 'system', 'system')
ON CONFLICT (id) DO NOTHING;

-- ------------------------------------------------------------
-- 3) 粗粒度资源级码退役
--    agent:manage / task:assign / review:approve / system:manage /
--    user:manage / role:manage 已被动作级码取代，先解绑再软删，避免误用。
--    （task:assign / review:approve 的等价能力由 subtask:reassign|redispatch、
--      review:add 承接，ADMIN 绑定在 V89 重建，能力不丢失。）
-- ------------------------------------------------------------
DELETE FROM sys_role_permission
WHERE permission_id IN (
    SELECT id FROM sys_permission
    WHERE code IN ('agent:manage', 'task:assign', 'review:approve',
                   'system:manage', 'user:manage', 'role:manage')
);

UPDATE sys_permission
SET deleted = 1, update_by = 'system'
WHERE code IN ('agent:manage', 'task:assign', 'review:approve',
               'system:manage', 'user:manage', 'role:manage');
