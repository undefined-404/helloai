-- ============================================================
-- 侧边栏一级分组：把平铺的 16 个一级菜单收进 4 个「目录型一级菜单」
-- 背景：G-012 菜单树 DB 化后仍是一级平铺，分组只靠 sort 跳号（1~10 / 155~170）手工维持，
--       用户看不见分组边界，且每加一个菜单都要重算跳号。本迁移把「目录」显式建模。
-- 口径（沿用 settings 既有形态，不引入新类型、不新增列）：
--       目录 = type=MENU + path 非空 + component 为空 + 有子节点
--       前端 dynamic.ts 的「聚合父节点」分支据此生成 redirect 路由，MainLayout 渲染为可折叠子菜单。
-- 幂等：目录节点 ON CONFLICT DO NOTHING；子节点 parent_id/sort 为绝对赋值，重复执行结果一致。
-- 影响：不改动任何权限码语义，分组节点本身不参与鉴权（前端菜单树按「自身授权 OR 有可见子孙」放行）。
-- ============================================================

-- ------------------------------------------------------------
-- 1. 目录型一级菜单（4 个）
--    id 200~203：现有 id 最大 133，200 段为菜单分组预留
-- ------------------------------------------------------------
INSERT INTO sys_permission (id, code, name, type, parent_id, path, icon, sort, create_by, update_by)
VALUES
    (200, 'group:task',    '任务协作',   'MENU', NULL, '/group/task',    'FolderOpened', 10, 'system', 'system'),
    (201, 'group:observe', '运行观测',   'MENU', NULL, '/group/observe', 'DataBoard',    20, 'system', 'system'),
    (202, 'group:agent',   'Agent 协作', 'MENU', NULL, '/group/agent',   'Cpu',          30, 'system', 'system'),
    (203, 'group:config',  '资源配置',   'MENU', NULL, '/group/config',  'Setting',      40, 'system', 'system')
ON CONFLICT DO NOTHING;

-- ------------------------------------------------------------
-- 2. 重挂子菜单 + 组内重排
--    sort 改 10 步长：插入新菜单时取相邻两项中点即可，不必整体重排
-- ------------------------------------------------------------
UPDATE sys_permission p
SET parent_id = v.parent_id,
    sort      = v.sort
FROM (VALUES
    -- 任务协作
    ('dashboard:view',  200, 10),
    ('task:create',     200, 20),
    ('task:view',       200, 30),
    ('subtask:view',    200, 40),
    ('review:view',     200, 50),
    -- 运行观测
    ('event:view',      201, 10),
    ('quality:view',    201, 20),
    -- Agent 协作
    ('agent:view',      202, 10),
    ('team:view',       202, 20),
    ('browser:view',    202, 30),
    ('activity:view',   202, 40),
    ('duty:view',       202, 50),
    ('inbox:view',      202, 60),
    -- 资源配置
    ('rule:view',       203, 10),
    ('attachment:view', 203, 20)
) AS v(code, parent_id, sort)
WHERE p.code = v.code
  AND p.type = 'MENU'
  AND p.deleted = 0;

-- ------------------------------------------------------------
-- 3. 置顶：个人工作台（与 dashboard 原同为 sort=1，顺序由 DB 决定，不稳定）
-- ------------------------------------------------------------
UPDATE sys_permission
SET parent_id = NULL,
    sort      = 0
WHERE code = 'workbench:view'
  AND type = 'MENU'
  AND deleted = 0;

-- ------------------------------------------------------------
-- 4. 系统设置保持一级、排最末
--    不并入「资源配置」：其下尚有 6 个二级菜单，而侧边栏只渲染两级；
--    并入会变成「三级祖先」导致 6 个子项不可见，且 MainLayout.settingsTarget
--    依赖 /settings 位于根层。
-- ------------------------------------------------------------
UPDATE sys_permission
SET parent_id = NULL,
    sort      = 50
WHERE code = 'settings:view'
  AND type = 'MENU'
  AND deleted = 0;

COMMENT ON COLUMN sys_permission.parent_id IS '父菜单 ID（NULL=一级菜单，type=MENU 时有效）；component 为空且有子节点者即「目录/分组」';
