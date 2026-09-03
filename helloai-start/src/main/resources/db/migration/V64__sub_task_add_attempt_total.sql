-- ============================================================
-- V64__sub_task_add_attempt_total.sql
-- 用途：子任务全局共享重试计数器（Phase 0 A3）
-- 背景：
--   P0 坑点 3「单一权威」：重分配 / 返工 / 超时 / 投递各层独立计数，
--   叠加后实际执行次数可放大到 45 次。本迁移为 sub_task 增加
--   attempt_total（全局共享预算），重分配熔断从 reassign_attempt_count
--   切换读取 attempt_total；后续 rework / timeout 逐步并入同一预算。
--   判定语义统一收敛在 RetryPolicy.exceedsMax，上限复用
--   helloai.dispatch.max-reassign-attempts（默认 5，不新建配置）。
-- 存量数据：一次性把已发生的重分配次数搬入 attempt_total，
--   避免切换后熔断阈值被重置（重新拥有满额预算）。
-- 参考：doc/design/HelloAI_Phase0_架构改造执行方案.md Epic-A A3
-- ============================================================

ALTER TABLE sub_task
    ADD COLUMN IF NOT EXISTS attempt_total INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN sub_task.attempt_total IS '全局共享重试计数器（Phase 0 A3），重分配熔断读取此值';

-- 存量搬迁：已发生的重分配次数计入共享预算
UPDATE sub_task
SET attempt_total = reassign_attempt_count
WHERE attempt_total = 0
  AND reassign_attempt_count IS NOT NULL
  AND reassign_attempt_count > 0;