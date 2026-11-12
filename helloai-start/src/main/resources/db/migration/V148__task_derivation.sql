-- ══════════════════════════════════════════════════════════════════════════
-- V148：派生式子任务数据模型 + 执行期重构会话血缘 + 重构/派生权限种子（原 V152 + V153 + V152-RBAC 合并）
--
-- 原模型把「细化」实现为同一 Task 内的子级草案（展开 = 建子 code + 软删父行），
-- 三个结构性问题：展开是改造类长事务、删父行使 depends_on 悬空、确认时要做
-- code→id 扁平化。ADR-008 改为「派生一个独立 Task」：
--   · 父 SubTask 不删，只置 AWAITING_DERIVED；派生 Task 整体完成后置 DONE
--   · 层级由 task.depth 表达（根 0，全局 ≤2），不再从 code 段数推导
--   · 草案期派生在父任务确认前就要落库，此时父 SubTask 还不存在
--     ⇒ 双锚：parent_sub_task_id（执行期）或 (parent_task_id, parent_draft_code)（草案期）
--
-- 本文件：
--   A. task 派生列 + 两条条件唯一索引（只有「活跃派生」参与唯一约束）
--   B. root_task_id 回填 + BEFORE INSERT 触发器：根任务 = 自身 id
--   C. sub_task 状态增加 AWAITING_DERIVED / RESTRUCTURING
--   D. requirement_conversation.source_sub_task_id：执行期重构会话血缘
--   E. RBAC：subtask:restructure / subtask:derive 两码 + 角色授予 + 落地断言
-- ══════════════════════════════════════════════════════════════════════════

-- ── A. task 派生列 ─────────────────────────────────────────────────────────
ALTER TABLE task
    ADD COLUMN IF NOT EXISTS parent_sub_task_id BIGINT,
    ADD COLUMN IF NOT EXISTS parent_task_id     BIGINT,
    ADD COLUMN IF NOT EXISTS parent_draft_code  VARCHAR(64),
    ADD COLUMN IF NOT EXISTS root_task_id       BIGINT,
    ADD COLUMN IF NOT EXISTS depth              INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS derivation_reason  VARCHAR(16),
    ADD COLUMN IF NOT EXISTS derivation_round   INT;

-- ── B. root_task_id 回填（根任务 = 自身 id） ───────────────────────────────
UPDATE task SET root_task_id = id WHERE root_task_id IS NULL;
ALTER TABLE task ALTER COLUMN root_task_id SET NOT NULL;

-- 「根 = 自身 id」由 DB 兜底，而不是要求每个建任务入口都记得写：
-- id 由 MyBatis-Plus ASSIGN_ID 在 INSERT 时才确定，应用层在同一语句里拿不到；
-- 派生任务显式写 parent.root_task_id 时本触发器不覆盖（只补 NULL）。
-- 这样「根链 id 恒非空」是一条库级不变量，新入口漏写不会静默造出找不到根的孤行。
CREATE OR REPLACE FUNCTION set_task_root_task_id() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.root_task_id IS NULL THEN
        NEW.root_task_id := NEW.id;
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS trg_task_root_task_id ON task;
CREATE TRIGGER trg_task_root_task_id
    BEFORE INSERT ON task
    FOR EACH ROW EXECUTE FUNCTION set_task_root_task_id();

ALTER TABLE task DROP CONSTRAINT IF EXISTS chk_task_depth;
ALTER TABLE task ADD CONSTRAINT chk_task_depth CHECK (depth >= 0 AND depth <= 2);

ALTER TABLE task DROP CONSTRAINT IF EXISTS chk_task_derivation_reason;
ALTER TABLE task ADD CONSTRAINT chk_task_derivation_reason CHECK (
    derivation_reason IS NULL OR derivation_reason IN ('PLAN', 'RESTRUCTURE')
);

-- 父锚二选一：执行期用 parent_sub_task_id，草案期用 (parent_task_id, parent_draft_code)。
-- 根任务（depth=0）两者皆空；派生行必须恰好有一种锚。
ALTER TABLE task DROP CONSTRAINT IF EXISTS chk_task_parent_anchor;
ALTER TABLE task ADD CONSTRAINT chk_task_parent_anchor CHECK (
    NOT (parent_sub_task_id IS NOT NULL AND parent_draft_code IS NOT NULL)
    AND (parent_draft_code IS NULL OR parent_task_id IS NOT NULL)
    AND (parent_sub_task_id IS NOT NULL OR parent_draft_code IS NOT NULL OR depth = 0)
    AND (derivation_reason IS NULL OR parent_sub_task_id IS NOT NULL OR parent_draft_code IS NOT NULL)
);

-- 活跃派生唯一：CANCELLED / 软删的派生不参与，允许同一父再次派生
CREATE UNIQUE INDEX IF NOT EXISTS uk_task_parent_sub_task_active
    ON task (parent_sub_task_id)
    WHERE parent_sub_task_id IS NOT NULL AND status <> 'CANCELLED' AND deleted = 0;

-- 草案期派生的活跃唯一：父任务尚未确认，父 SubTask 不存在
CREATE UNIQUE INDEX IF NOT EXISTS uk_task_parent_draft_active
    ON task (parent_task_id, parent_draft_code)
    WHERE parent_sub_task_id IS NULL AND parent_draft_code IS NOT NULL
      AND status <> 'CANCELLED' AND deleted = 0;

CREATE INDEX IF NOT EXISTS idx_task_root_task
    ON task (root_task_id) WHERE deleted = 0;

COMMENT ON COLUMN task.parent_sub_task_id IS
    '执行期派生的父 SubTask id（软引用，无 FK）；派生 Task 完成后据此把父 SubTask 置 DONE';
COMMENT ON COLUMN task.parent_task_id IS
    '草案期派生的父 Task id：派生 Task 在父任务确认前落库，此时父 SubTask 尚不存在';
COMMENT ON COLUMN task.parent_draft_code IS
    '草案期派生的父草案 code；父任务确认物化 SubTask 时回填 parent_sub_task_id 并清空本列';
COMMENT ON COLUMN task.root_task_id IS
    '根链 id（根任务 = 自身 id）；看板与全链查询按它聚合';
COMMENT ON COLUMN task.depth IS
    '派生层级（根 0，全局 ≤2）；不再从草案 code 段数推导';
COMMENT ON COLUMN task.derivation_reason IS
    'PLAN = 草案期派生；RESTRUCTURE = 执行期重构派生';
COMMENT ON COLUMN task.derivation_round IS
    '同一父的第几次派生（从 1 起），区分合法重复派生与 bug';

-- ── C. sub_task 状态约束 ───────────────────────────────────────────────────
-- AWAITING_DERIVED / RESTRUCTURING 已由 V146 的最终 CHECK 纳入（合并基线），
-- 此处不再重复重建——重复定义会让后一个文件覆盖前一个，把已退役的
-- PENDING_PLAN_REVIEW 又加回去（2026-11-11 收敛时实测踩到）。

-- ── D. 执行期重构会话血缘 ──────────────────────────────────────────────────
-- 执行期「拆出去当主任务」需要一条与子任务绑定的需求会话：
--   · 会话本身仍挂在父任务下（task_id = X），供对话与定稿使用
--   · source_sub_task_id 指向被重构的 A2，定稿建 Y 时据此挂 parent_sub_task_id
-- 普通需求/助理会话该列为空；唯一性是「一个子任务同时只有一条 ACTIVE 会话」，
-- 由应用层保证（可重复重构，历史会话保留，故不加唯一索引）。
ALTER TABLE requirement_conversation
    ADD COLUMN IF NOT EXISTS source_sub_task_id BIGINT;

COMMENT ON COLUMN requirement_conversation.source_sub_task_id IS
    '执行期重构派生的来源子任务（仅 RESTRUCTURING 态派生会话非空）；普通需求会话为空';

CREATE INDEX IF NOT EXISTS idx_requirement_conversation_source_sub_task
    ON requirement_conversation (source_sub_task_id)
    WHERE source_sub_task_id IS NOT NULL;


-- ── E. RBAC：执行期重构 / 派生入口权限（原 V152 并入）──────────────────────
-- ADR-008 §19.8：草案期派生复用 task:plan；执行期重构 / 拆出去分别需要
-- subtask:restructure / subtask:derive。此前的实现复用了 subtask:status/edit，
-- 粒度不足（2026-11-11 审计）。种子与落地断言放在同一文件，避免「埋雷 + 排雷」
-- 两个迁移；固定 id 204/205 若被占用，ON CONFLICT 会静默跳过，故末尾按 code 断言。
INSERT INTO sys_permission (id, code, name, type, sort, create_by, update_by)
VALUES
    (204, 'subtask:restructure', '子任务-进入重构/整改', 'API', 123, 'system', 'system'),
    (205, 'subtask:derive',      '子任务-拆出去派生',     'API', 124, 'system', 'system')
ON CONFLICT (id) DO NOTHING;

INSERT INTO sys_role_permission (id, role_id, permission_id, create_by, update_by)
SELECT 9000000000000400000 + p.id, 2, p.id, 'system', 'system'
FROM sys_permission p
WHERE p.deleted = 0
  AND p.code IN ('subtask:restructure', 'subtask:derive')
ON CONFLICT DO NOTHING;

INSERT INTO sys_role_permission (id, role_id, permission_id, create_by, update_by)
SELECT 9000000000000500000 + p.id, 3, p.id, 'system', 'system'
FROM sys_permission p
WHERE p.deleted = 0
  AND p.code IN ('subtask:restructure', 'subtask:derive')
ON CONFLICT DO NOTHING;

DO
$$
    DECLARE
        missing_code  TEXT;
        missing_grant TEXT;
    BEGIN
        SELECT string_agg(want.code, ', ') INTO missing_code
        FROM (VALUES ('subtask:restructure'), ('subtask:derive')) AS want(code)
        WHERE NOT EXISTS (
            SELECT 1 FROM sys_permission p
            WHERE p.code = want.code AND p.deleted = 0 AND p.type = 'API'
        );
        IF missing_code IS NOT NULL THEN
            RAISE EXCEPTION '[V148] 权限码缺失（id 204/205 可能已被占用）：%', missing_code;
        END IF;

        SELECT string_agg(want.code || ' -> role ' || want.role_id, ', ') INTO missing_grant
        FROM (VALUES ('subtask:restructure', 2), ('subtask:restructure', 3),
                     ('subtask:derive', 2),      ('subtask:derive', 3)) AS want(code, role_id)
        WHERE NOT EXISTS (
            SELECT 1 FROM sys_permission p
            JOIN sys_role_permission rp ON rp.permission_id = p.id
            WHERE p.code = want.code AND p.deleted = 0 AND rp.role_id = want.role_id
        );
        IF missing_grant IS NOT NULL THEN
            RAISE EXCEPTION '[V148] 角色授予缺失：%', missing_grant;
        END IF;

        RAISE NOTICE '[V148] 执行期重构/派生权限点与角色授予齐备';
    END
$$;

-- ── 验证日志 ───────────────────────────────────────────────────────────────
DO $$
BEGIN
    RAISE NOTICE '[V148] 派生列/索引/触发器、重构会话血缘、重构/派生权限种子 已就绪';
END $$;

-- ── 版本映射（2026-11-10 合并）──────────────────────────────────────────────
-- 原 V152 task_derivation_and_states            ┐
-- 原 V153 requirement_conversation_source_sub_task ┘─→ 本文件
-- 2026-11-11 收敛：原 V152（RBAC 种子 + 自校验）并入本文件；原 V150（存量迁入）/
--   V151（减法）取消，基线直接为最终形态，升级路径不再支持。
