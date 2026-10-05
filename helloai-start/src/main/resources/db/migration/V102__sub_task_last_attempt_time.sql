-- ============================================================
-- V102__sub_task_last_attempt_time.sql
-- 用途：子任务「上次重派尝试时刻」独立列（修复换人/重新调度被退避窗口静默拦截）
-- 背景：
--   G-015 B2.2 重派退避窗口此前以 sub_task.update_time 为时钟，javadoc 假设
--   「只有 incrementAttemptTotal 会写 update_time」。但实际 block() / resume() /
--   changeStatus() / resetToPendingForDispatch() 全部经 updateById 写 update_time。
--   于是「换人」入口 redispatchInProgress（先 block() 再 dispatchBlockedSubTask()）
--   在 block() 时把 update_time 改成 now，紧接着闸门读到 now ⇒ nextAllowed = now + 600s
--   ⇒ 必然拦截 —— 只要 attempt_total >= 1，「换人」100% 静默失败（子任务 804 实测）。
--
--   本迁移把退避时钟从复用的 update_time 解绑到独立列 last_attempt_time：
--     - 只由 SubTaskMapper.incrementAttemptTotal 原子写入（与 attempt_total 同批，单一权威）；
--     - updateById（block/resume/changeStatus/reset 等）显式不写本列，杜绝自我刷新；
--     - 退避闸门 isReassignBlockedOrEscalate 只读本列。
--
--   存量数据一次性搬迁：attempt_total > 0 的行以 update_time 作为最近一次尝试时刻回填，
--   保持迁移前后退避语义连续（否则旧窗口会被重置为一过即放行）。
-- 幂等：ADD COLUMN IF NOT EXISTS + 条件回填（仅 NULL 行），重复执行无副作用。
-- 注意：不修改任何已 apply 的 V*.sql（Flyway 校验和不可变，CODE_STYLE §16）。
-- ============================================================

ALTER TABLE sub_task
    ADD COLUMN IF NOT EXISTS last_attempt_time TIMESTAMPTZ;

COMMENT ON COLUMN sub_task.last_attempt_time IS
    '上次重派尝试时刻（G-015 B2.2 退避时钟）；仅 incrementAttemptTotal 原子写入，updateById 不写';

-- 存量搬迁：已发生过重派（attempt_total > 0）的行以 update_time 回填，保持退避窗口连续
UPDATE sub_task
SET last_attempt_time = update_time
WHERE last_attempt_time IS NULL
  AND attempt_total IS NOT NULL
  AND attempt_total > 0
  AND update_time IS NOT NULL;

-- 验证日志（启动时输出列存在性与回填条数）
DO $$
DECLARE
    present_columns INTEGER;
    backfilled_rows BIGINT;
BEGIN
    SELECT COUNT(*) INTO present_columns
    FROM information_schema.columns
    WHERE table_name = 'sub_task'
      AND column_name = 'last_attempt_time';

    SELECT COUNT(*) INTO backfilled_rows
    FROM sub_task
    WHERE last_attempt_time IS NOT NULL;

    RAISE NOTICE '[V102] sub_task.last_attempt_time 列已就绪，已存在列数 = %，已回填行数 = %',
        present_columns, backfilled_rows;
END $$;
