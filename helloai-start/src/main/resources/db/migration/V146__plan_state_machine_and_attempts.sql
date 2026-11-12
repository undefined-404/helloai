-- ══════════════════════════════════════════════════════════════════════════
-- V146：拆解状态机 + 任务租约 + 操作栅栏（原 V146 + V147 + V148 合并）
--
-- 一个拆解事件需要三件事同时成立，缺一都会留下「卡住」形态：
--   1) 任务状态区分「机器正在拆」与「草案等你确认」——两者混用会让超时巡检
--      只能靠 `NOT EXISTS (草案)` 打补丁判活；
--   2) 任务级租约（`decompose_lease_until`）回答「还卡在拆解中吗」。不能用
--      `update_time` 心跳：拆解线程调模型的十几分钟里一次库都不写，它无法自证活着；
--   3) 操作级栅栏（`task_plan_attempt`）回答「那次尝试还有效吗、该不该重试」。
--      只有任务级失活判据时，进程崩在「CAS 改状态」与「派 @Async」之间会留下
--      「PLANNING 但没人干活」，只能等租约过期。
--
-- 执行表状态与列直接为最终形态（2026-11-11 收敛）：
--   草案落点只有 `task_plan_draft`，`sub_task` 不再保留 `decompose_attempt_id` /
--   `draft_seq` 过渡列；`chk_sub_task_status` 直接去掉 `PENDING_PLAN_REVIEW`。
--
-- 合并说明：原 V146（租约/状态）、V147（序号回填）、V148（attempt 表）是同一关注点
--   的分步提交，schema 上不应拆成三段。版本号映射见文件末尾。
-- ══════════════════════════════════════════════════════════════════════════

-- ── A. 任务租约 + 本次拆解标识 ─────────────────────────────────────────────
ALTER TABLE task ADD COLUMN IF NOT EXISTS decompose_lease_until TIMESTAMPTZ;

COMMENT ON COLUMN task.decompose_lease_until IS
    '拆解租约到期时刻（进入 PLANNING 时写入 = now + planning-timeout-minutes）；'
    '巡检按「租约过期」判活，不再按 update_time 心跳。NULL = 非拆解中或历史行';

-- attemptId 必须在**入口**（beginDecompose）生成并落库，不能由异步段现生成：
-- 异步段每次执行都会得到新 UUID，那样唯一索引永远不冲突，幂等形同虚设。
ALTER TABLE task ADD COLUMN IF NOT EXISTS decompose_attempt_id VARCHAR(64);

COMMENT ON COLUMN task.decompose_attempt_id IS
    '当前/最近一次拆解的 attemptId（UUID，进入 PLANNING 时生成）；子任务据此归组与去重';

-- 部分索引：巡检只扫 PLANNING，索引不必覆盖其余状态
CREATE INDEX IF NOT EXISTS idx_task_decompose_lease
    ON task(decompose_lease_until)
    WHERE status = 'PLANNING' AND deleted = 0;

-- ── B. 任务状态：待确认独立成状态 ──────────────────────────────────────────
ALTER TABLE task DROP CONSTRAINT IF EXISTS chk_task_status;

ALTER TABLE task
    ADD CONSTRAINT chk_task_status CHECK (
        status IN ('PENDING', 'PLANNING', 'AWAITING_PLAN_CONFIRM',
                   'IN_PROGRESS', 'DONE', 'CANCELLED')
    );

-- ── B2. 子任务状态：合并基线直接为最终态 ──────────────────────────────────
-- 草案不再落执行表（PENDING_PLAN_REVIEW 随暂存区唯一事实源退场），执行行只保留
-- 派发 / 执行 / 派生 / 重构态。空库重建与任何新环境都从此约束开始。
ALTER TABLE sub_task DROP CONSTRAINT IF EXISTS chk_sub_task_status;
ALTER TABLE sub_task
    ADD CONSTRAINT chk_sub_task_status CHECK (
        status IN (
            'PENDING', 'ASSIGNED', 'IN_PROGRESS', 'PAUSED', 'REVIEW',
            'DONE', 'REWORK', 'BLOCKED', 'CANCELLED', 'DEAD_LETTER',
            'AWAITING_DERIVED', 'RESTRUCTURING'
        )
    );

-- ── D. 存量订正：草案在库的 PLANNING ⇒ 待确认 ──────────────────────────────
-- 不做这一步，新代码 confirmPlan 守卫（要求 AWAITING_PLAN_CONFIRM）会把存量
-- 「草案已生成、等用户确认」的任务全部拒掉，用户只能手工改库才能确认。
UPDATE task t
SET status = 'AWAITING_PLAN_CONFIRM'
WHERE t.status = 'PLANNING'
  AND t.deleted = 0
  AND EXISTS (
      SELECT 1 FROM sub_task s
      WHERE s.task_id = t.id
        AND s.status = 'PENDING_PLAN_REVIEW'
        AND s.deleted = 0
  );

-- ── E. 存量 PLANNING 补租约与 attemptId ────────────────────────────────────
-- 租约用 update_time 起算（旧口径的超时起点），留 15 分钟余量：既让仍在跑的拆解
-- 有机会续上，也保证真正卡死的行在下一轮巡检即被回收。
UPDATE task t
SET decompose_lease_until = t.update_time + INTERVAL '15 minutes'
WHERE t.status = 'PLANNING'
  AND t.deleted = 0
  AND t.decompose_lease_until IS NULL;

-- 存量 PLANNING 补 attemptId：升级瞬间可能有拆解正在跑，若为空会被异步段判为
-- 「PLANNING 但无 attemptId」直接跳过。给确定性值（legacy-<id>）而非随机值，
-- 便于在 timeline / 草案行上直接看出「这是升级前发起的拆解」。
UPDATE task t
SET decompose_attempt_id = 'legacy-' || t.id::text
WHERE t.status = 'PLANNING'
  AND t.deleted = 0
  AND t.decompose_attempt_id IS NULL;

-- ── G. 操作栅栏：task_plan_attempt ─────────────────────────────────────────
-- 一行 = 一次操作事件的一次尝试。`batch_key` 是幂等主键，`attempt_no` 区分重试次数。
-- 五态结论在落库那一刻判出「要不要重试」（失败不落通用值，事后没人能重判）。
-- 动作值只保留当前真实写入/保留语义的六类：EXPAND（同 Task 内展开）随 ADR-008 下线。
CREATE TABLE IF NOT EXISTS task_plan_attempt (
    id             BIGINT       NOT NULL PRIMARY KEY,
    task_id        BIGINT       NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    batch_key      VARCHAR(64)  NOT NULL,
    action         VARCHAR(16)  NOT NULL,
    status         VARCHAR(24)  NOT NULL,
    attempt_no     INT          NOT NULL DEFAULT 1,
    -- 解析阶段写入：本批**应落几条**。撞幂等键后用它判「内容是否完整」，
    -- 而不是判「是否存在」——存在但残缺的批次被当成已完成，就永远不会再补。
    expected_count INT,
    lease_until    TIMESTAMPTZ,
    owner_node     VARCHAR(64),
    outcome_note   TEXT,
    create_by      VARCHAR(64)  NOT NULL DEFAULT '',
    update_by      VARCHAR(64)  NOT NULL DEFAULT '',
    create_time    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    remark         VARCHAR(255),
    deleted        SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT chk_plan_attempt_status CHECK (
        status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED_TERMINAL', 'FAILED_RETRYABLE')
    ),
    CONSTRAINT chk_plan_attempt_action CHECK (
        action IN ('DECOMPOSE', 'REGENERATE', 'CONFIRM', 'ABANDON', 'DERIVE', 'RESTRUCTURE')
    )
);

COMMENT ON TABLE task_plan_attempt IS
    '拆解操作的 Outbox 意图 + 栅栏日志：一行 = 一次操作事件的一次尝试';
COMMENT ON COLUMN task_plan_attempt.batch_key IS
    '本次操作事件的幂等键（首拆 = task.decompose_attempt_id；其余动作按各自事件键）';
COMMENT ON COLUMN task_plan_attempt.attempt_no IS
    '同一 batch_key 的第几次尝试；超过当前有效值即「本次尝试已过期」，写结果前须 CAS 校验';
COMMENT ON COLUMN task_plan_attempt.expected_count IS
    '本批应落草案条数（解析阶段写入）；撞幂等键后用于判「内容完整」而非「存在」';
COMMENT ON COLUMN task_plan_attempt.status IS
    '五态：PENDING（已登记无人接）/ RUNNING（已抢占）/ SUCCEEDED / '
    'FAILED_TERMINAL（重试同样失败，不重试）/ FAILED_RETRYABLE（可重试，attempt_no+1）';
COMMENT ON COLUMN task_plan_attempt.owner_node IS
    '抢占者标识（多实例诊断用）；为空 = 尚未被任何节点接手';

-- 栅栏唯一键：同一 batch_key 的第 N 次尝试只允许一行。
-- 挡「悬挂」（A 超时被判失败 → B 接手 → A 苏醒想写结果）与「空回滚」（无记录却回滚）。
CREATE UNIQUE INDEX IF NOT EXISTS uk_plan_attempt_key
    ON task_plan_attempt(batch_key, attempt_no) WHERE deleted = 0;

-- 派发器扫描索引：只覆盖「待跑」与「在跑」，避免全表扫。
CREATE INDEX IF NOT EXISTS idx_plan_attempt_dispatch
    ON task_plan_attempt(status, lease_until)
    WHERE deleted = 0 AND status IN ('PENDING', 'RUNNING');

COMMENT ON INDEX uk_plan_attempt_key IS '栅栏：batch_key + attempt_no 唯一（防悬挂 / 防空回滚）';
COMMENT ON INDEX idx_plan_attempt_dispatch IS '派发器扫描：PENDING 待抢占 / RUNNING 查租约过期';

-- ── 验证日志 ───────────────────────────────────────────────────────────────
DO $$
BEGIN
    RAISE NOTICE '[V146] 状态机/租约/attempt 栅栏已就绪（执行表为最终形态）';
END $$;

-- ── 版本映射（2026-11-10 合并）──────────────────────────────────────────────
-- 原 V146 decompose_lease_and_awaiting_confirm ┐
-- 原 V147 backfill_draft_seq                   ├─→ 本文件
-- 原 V148 plan_attempt_outbox                  ┘

-- 2026-11-11 收敛：原 V150（存量草案迁入）/ V151（不可逆减法）/ V152（RBAC 种子）取消独立文件——
--   本基线不再创建过渡列与旧状态值，空库/重建链直接为最终形态；RBAC 种子并入 V148。
--   升级路径不再支持，存量环境必须 drop schema 重建。
