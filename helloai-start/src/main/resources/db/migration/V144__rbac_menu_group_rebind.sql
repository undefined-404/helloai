-- ============================================================
-- 菜单分组回挂修复（V143 的跨环境版本）
--
-- 背景：V143 用固定 id 200~203 插入分组，再按固定 id 回挂子菜单。
--       若目标环境 id 200~203 已被其他菜单占用，或 group:* 已存在但 id 不同，
--       V143 的 INSERT 会被 ON CONFLICT DO NOTHING 静默跳过，随后子菜单仍被挂到固定 id，
--       轻则挂错父节点，重则整组不可见。
--
-- 本迁移不改 V143（已应用的迁移文件不可修改，否则 Flyway 校验和失败），
-- 改为按 code 解析分组 id 后回挂，并补齐所有 ACTIVE 一级菜单、统一组内 sort。
-- 幂等：分组按 code 查存在性，缺失才插入；回挂为绝对赋值，重复执行结果一致。
-- ============================================================

-- ------------------------------------------------------------
-- 1. 确保 4 个分组存在（按 code 判定；缺失时取当前最大 id + 1）
-- ------------------------------------------------------------
DO $$
DECLARE
    g RECORD;
    new_id BIGINT;
BEGIN
    FOR g IN
        SELECT * FROM (VALUES
            ('group:task',    '任务协作',   '/group/task',    'FolderOpened', 10),
            ('group:observe', '运行观测',   '/group/observe', 'DataBoard',    20),
            ('group:agent',   'Agent 协作', '/group/agent',   'Cpu',          30),
            ('group:config',  '资源配置',   '/group/config',  'Setting',      40)
        ) AS t(code, name, path, icon, sort)
    LOOP
        IF NOT EXISTS (
            SELECT 1 FROM sys_permission WHERE code = g.code AND deleted = 0
        ) THEN
            SELECT COALESCE(MAX(id), 0) + 1 INTO new_id FROM sys_permission;
            INSERT INTO sys_permission (id, code, name, type, parent_id, path, icon, sort, create_by, update_by)
            VALUES (new_id, g.code, g.name, 'MENU', NULL, g.path, g.icon, g.sort, 'system', 'system');
        END IF;
    END LOOP;
END $$;

-- 分组排序兜底：已有分组但 sort 不在 10/20/30/40 时纠正
UPDATE sys_permission p
SET sort = v.sort
FROM (VALUES
    ('group:task', 10),
    ('group:observe', 20),
    ('group:agent', 30),
    ('group:config', 40)
) AS v(code, sort)
WHERE p.code = v.code
  AND p.type = 'MENU'
  AND p.deleted = 0;

-- ------------------------------------------------------------
-- 2. 子菜单回挂：父 id 从 code 解析，不依赖固定 id
--    覆盖 V143 已列的 15 项 + 可能在其他环境仍为 ACTIVE 的 reward/deadletter
-- ------------------------------------------------------------
UPDATE sys_permission p
SET parent_id = g.id,
    sort      = v.sort
FROM (VALUES
    -- 任务协作
    ('dashboard:view',   'group:task',    10),
    ('task:create',      'group:task',    20),
    ('task:view',        'group:task',    30),
    ('subtask:view',     'group:task',    40),
    ('review:view',      'group:task',    50),
    ('deadletter:view',  'group:task',    60),
    -- 运行观测
    ('event:view',       'group:observe', 10),
    ('quality:view',     'group:observe', 20),
    -- Agent 协作
    ('agent:view',       'group:agent',   10),
    ('team:view',        'group:agent',   20),
    ('browser:view',     'group:agent',   30),
    ('activity:view',    'group:agent',   40),
    ('duty:view',        'group:agent',   50),
    ('inbox:view',       'group:agent',   60),
    ('reward:view',      'group:agent',   70),
    -- 资源配置
    ('rule:view',        'group:config',  10),
    ('attachment:view',  'group:config',  20)
) AS v(code, group_code, sort)
JOIN sys_permission g
  ON g.code = v.group_code
 AND g.type = 'MENU'
 AND g.deleted = 0
WHERE p.code = v.code
  AND p.type = 'MENU'
  AND p.deleted = 0;

-- ------------------------------------------------------------
-- 3. 系统设置子项排序统一（原为 18~22 且 19 重复，改为 10 步长）
-- ------------------------------------------------------------
UPDATE sys_permission p
SET sort = v.sort
FROM (VALUES
    ('user:view', 10),
    ('role:view', 20),
    ('menu:view', 30),
    ('permission:view', 40),
    ('depart:view', 50),
    ('platform-config:view', 60)
) AS v(code, sort)
WHERE p.code = v.code
  AND p.type = 'MENU'
  AND p.deleted = 0
  AND p.parent_id = (
      SELECT id FROM sys_permission WHERE code = 'settings:view' AND deleted = 0 LIMIT 1
  );

-- ------------------------------------------------------------
-- 4. 一级位置：个人工作台置顶、系统设置排末
-- ------------------------------------------------------------
UPDATE sys_permission
SET parent_id = NULL,
    sort      = 0
WHERE code = 'workbench:view'
  AND type = 'MENU'
  AND deleted = 0;

UPDATE sys_permission
SET parent_id = NULL,
    sort      = 50
WHERE code = 'settings:view'
  AND type = 'MENU'
  AND deleted = 0;

-- ------------------------------------------------------------
-- 5. 兜底告警：任何仍为一级的可见 MENU（未纳入分组）打 WARNING。
--    不直接失败：V143 曾在目标库被手工应用，目标环境可能存在本仓未登记的自定义菜单；
--    迁移失败会阻断部署，而告警足以让运维发现「分组结构未覆盖完整」。
-- ------------------------------------------------------------
DO $$
DECLARE
    leftovers TEXT;
BEGIN
    SELECT string_agg(code || '#' || id, ', ' ORDER BY sort, id)
    INTO leftovers
    FROM sys_permission
    WHERE type = 'MENU'
      AND deleted = 0
      AND parent_id IS NULL
      AND COALESCE(hidden, 0) = 0
      AND code NOT IN (
          'workbench:view', 'settings:view',
          'group:task', 'group:observe', 'group:agent', 'group:config'
      );

    IF leftovers IS NOT NULL THEN
        RAISE WARNING '菜单分组未覆盖以下一级菜单，请补充后续迁移映射: %', leftovers;
    END IF;
END $$;