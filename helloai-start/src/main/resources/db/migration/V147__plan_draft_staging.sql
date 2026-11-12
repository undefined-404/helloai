-- ══════════════════════════════════════════════════════════════════════════
-- V147：草案暂存区（原 V149 + V150 + V151 + V154 合并）
--
-- 为什么需要独立暂存区：
--   `sub_task` 一张表同时承担「草案暂存」与「执行任务」两种语义，由此衍生出
--   一连串补丁：物理删除要反证「确实只是草案」、批内序号排序与编号两套口径、
--   删草稿会让 depends_on 悬空、每加一个草案期字段都要同步 SubTaskMapper 的显式列清单。
--   根因是**身份体系错位**：草案的身份是「第几号位置」，执行任务的身份是「数据库行 id」。
--
-- 本文件的三个不变量：
--   1) `code` 是逻辑位置身份（`<yyyyMMdd><seq:04d>[-<seq:03d>…]`），定宽 ⇒ 字典序 = 树前序；
--   2) `depends_on_codes` 存 code 数组而不是 id 数组——位置作废重建后引用不断；
--   3) 确认是显式一态（`CONFIRMED`）。只软删不改状态，读侧任何漏过滤 `deleted`
--      的查询都会把已转正草案当待确认再确认一次。
--
-- 执行表侧的 `sub_task.draft_code` 是位置身份的投影，用于双写定位与转正后追溯；
-- 唯一性只由暂存区 `uk_plan_draft_code` 保证，执行表不重复约束。
--
-- 删除级联：两张拆解痕迹表都是任务级数据，没有独立生命周期；`task_id` 外键直接
--   `ON DELETE CASCADE`，避免再在应用层维护「新表要不要加进级联清单」这份易漏清单。
-- ══════════════════════════════════════════════════════════════════════════

-- ── A. 暂存区表 ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS task_plan_draft (
    id                BIGINT       NOT NULL PRIMARY KEY,
    task_id           BIGINT       NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    -- 逻辑位置身份：<yyyyMMdd><seq:04d>，子层 -<seq:03d>。定宽 ⇒ 字典序 = 树的前序
    code              VARCHAR(64)  NOT NULL,
    -- 草案轮次，同 task 内递增；读侧默认只读 max(round) 那一轮
    round             SMALLINT     NOT NULL DEFAULT 1,
    -- 幂等键：本次操作事件（首拆 = task.decompose_attempt_id；其余动作按各自事件键）
    batch_key         VARCHAR(64)  NOT NULL,
    -- 三态：ACTIVE 可编辑 / DISCARDED 用户废弃 / CONFIRMED 已转正
    node_state        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    -- 本批拆解内的序号（1-based；重生成/人工新建为 NULL）。读侧编号与 blockedBy 锚用。
    draft_seq         INTEGER,
    title             VARCHAR(255) NOT NULL,
    content           TEXT,
    deliverable       TEXT,
    acceptance        TEXT,
    acceptance_kind   VARCHAR(16)  NOT NULL DEFAULT 'AUTOMATED',
    priority          VARCHAR(10)  NOT NULL DEFAULT 'MEDIUM',
    contract          SMALLINT     NOT NULL DEFAULT 0,
    required_skills   JSONB        NOT NULL DEFAULT '[]'::jsonb,
    constraints       TEXT,
    uncertainties     JSONB        NOT NULL DEFAULT '[]'::jsonb,
    -- code 数组，不是 id 数组 —— 这是整个方案的核心
    depends_on_codes  JSONB        NOT NULL DEFAULT '[]'::jsonb,
    -- plannerAgentId / plannerAgentName / planGeneratedAt /
    -- acceptanceKindDeclared / acceptancePreconditions / gateFindings
    context           JSONB,
    create_by         VARCHAR(64)  NOT NULL DEFAULT '',
    update_by         VARCHAR(64)  NOT NULL DEFAULT '',
    create_time       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    remark            VARCHAR(255),
    deleted           SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT chk_plan_draft_state CHECK (
        node_state IN ('ACTIVE', 'DISCARDED', 'CONFIRMED')
    )
);

COMMENT ON TABLE task_plan_draft IS
    '草案暂存区：草案期以 code（位置）为引用锚点，确认时一次性扁平化为 sub_task 的真实 id';
COMMENT ON COLUMN task_plan_draft.code IS
    '逻辑位置身份（<yyyyMMdd><seq:04d>[-<seq:03d>…]）；日期前缀取 task.create_time 不取 now()';
COMMENT ON COLUMN task_plan_draft.round IS
    '草案轮次（同 task 内递增）；确认即终止，确认后唯一的新轮入口是「整体废除」';
COMMENT ON COLUMN task_plan_draft.batch_key IS
    '本次操作事件的幂等键（首拆 = task.decompose_attempt_id；其余动作按各自事件键）';
COMMENT ON COLUMN task_plan_draft.node_state IS
    'ACTIVE 可编辑 / DISCARDED 用户废弃 / CONFIRMED 已转正';
COMMENT ON COLUMN task_plan_draft.draft_seq IS
    '本批拆解内的草案序号（1-based；重生成/人工新建为 NULL）。'
    '读侧编号与 blockedBy 锚用；唯一性由生成方（落库顺序）保证，不重复约束';
COMMENT ON COLUMN task_plan_draft.depends_on_codes IS
    '依赖的 code 数组（不是 id）；确认时才翻译成 code → 真实 sub_task.id';

-- 同一任务内 code 唯一（软删让位：旧批次整轮作废后，同位置可重新拆解）
CREATE UNIQUE INDEX IF NOT EXISTS uk_plan_draft_code
    ON task_plan_draft(task_id, code) WHERE deleted = 0;

-- 幂等：同一次操作事件产出的每个 code 只允许一行
CREATE UNIQUE INDEX IF NOT EXISTS uk_plan_draft_batch
    ON task_plan_draft(batch_key, code) WHERE deleted = 0;

-- 读侧主查询：某任务的当前有效草案
CREATE INDEX IF NOT EXISTS idx_plan_draft_task
    ON task_plan_draft(task_id, node_state, deleted);

-- 读侧默认只读「当前轮」：max(round) 那一轮
CREATE INDEX IF NOT EXISTS idx_plan_draft_round
    ON task_plan_draft(task_id, round, node_state, deleted);

COMMENT ON INDEX uk_plan_draft_code IS '位置唯一：同任务内 code 不重复（软删让位）';
COMMENT ON INDEX uk_plan_draft_batch IS '事件幂等：同 batch_key 同 code 只落一行';
COMMENT ON INDEX idx_plan_draft_round IS '读侧只取当前轮（max round）';

-- ── B. 执行表侧位置身份副本 ────────────────────────────────────────────────
ALTER TABLE sub_task ADD COLUMN IF NOT EXISTS draft_code VARCHAR(64);

COMMENT ON COLUMN sub_task.draft_code IS
    '草案的位置身份（<yyyyMMdd><seq:04d>[-<seq:03d>…]）；非拆解路径创建的子任务为 NULL。'
    '与 task_plan_draft.code 同值，用于双写定位与转正后追溯；唯一性由暂存区保证，此处不重复约束';

-- 部分索引：只有草案转正后的行才有 code，全表绝大多数行为 NULL，不必覆盖
CREATE INDEX IF NOT EXISTS idx_sub_task_draft_code
    ON sub_task(task_id, draft_code)
    WHERE draft_code IS NOT NULL AND deleted = 0;

COMMENT ON INDEX idx_sub_task_draft_code IS
    '按位置身份反查执行任务（双写定位 / 转正后追溯）';

-- ── 验证日志 ───────────────────────────────────────────────────────────────
DO $$
DECLARE
    draft_cnt   BIGINT;
    active_cnt  BIGINT;
    with_code   BIGINT;
BEGIN
    SELECT count(*) INTO draft_cnt FROM task_plan_draft;
    SELECT count(*) INTO active_cnt FROM task_plan_draft
        WHERE node_state = 'ACTIVE' AND deleted = 0;
    SELECT count(*) INTO with_code FROM sub_task
        WHERE deleted = 0 AND draft_code IS NOT NULL;

    RAISE NOTICE '[V147] 草案暂存区已就绪：全表 %, ACTIVE %, 执行表已有 code %',
        draft_cnt, active_cnt, with_code;
END $$;

-- ── 版本映射（2026-11-10 合并）──────────────────────────────────────────────
-- 原 V149 plan_draft_table            ┐
-- 原 V150 sub_task_draft_code        │
-- 原 V151 plan_draft_draft_seq       ├─→ 本文件
-- 原 V154 plan_trace_cascade_on_delete ┘（FK CASCADE 已并入建表）

-- 2026-11-11 收敛：原「暂存区序号 ← sub_task.draft_seq」回填段随过渡列一起取消；
--   sub_task 不再有 draft_seq，暂存区序号只由拆解落库路径写入。原 V150/V151 独立
--   文件取消，基线直接为最终形态。
