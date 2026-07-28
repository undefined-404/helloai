-- ============================================================
-- V25__sub_task_dead_letter_status.sql
-- 用途：子任务死信状态 —— 重分配熔断后转入人工兜底池
-- 背景：
--   V24 的重分配熔断在 reassign_attempt_count 达到阈值后直接把子任务
--   置为 CANCELLED（终态），导致：
--   1. 无法与"人工主动取消"区分；
--   2. 没有任何人工恢复入口，只能重新建一条子任务，上下文与审计链断裂。
--   本迁移在 sub_task.status 的 CHECK 约束中加入 DEAD_LETTER：
--   熔断触发后子任务进入 DEAD_LETTER（死信池），由人工通过
--   POST /sub-tasks/dead-letter/redispatch/{id} 直接指派给指定 Agent
--   （计数清零 + ASSIGNED），或人工放弃（CANCELLED）。
-- 机制：
--   - PENDING/ASSIGNED/IN_PROGRESS/BLOCKED/REWORK → DEAD_LETTER（系统熔断）
--   - DEAD_LETTER → ASSIGNED（人工指派兜底）
--   - DEAD_LETTER → CANCELLED（人工放弃）
--   - DEAD_LETTER 对所有自动重派/兜底定时任务不可见（它们只扫 PENDING/ASSIGNED 等）
-- 参考：用户反馈 2026-07-28 — "自动降级改派无限循环，超过 5 次应进入死信由人工兜底"
-- ============================================================

ALTER TABLE sub_task
    DROP CONSTRAINT IF EXISTS chk_sub_task_status;

ALTER TABLE sub_task
    ADD CONSTRAINT chk_sub_task_status CHECK (
        status IN ('PENDING', 'ASSIGNED', 'IN_PROGRESS', 'PAUSED', 'REVIEW', 'DONE', 'REWORK', 'BLOCKED', 'CANCELLED', 'DEAD_LETTER')
    );

-- 验证日志
DO $$
BEGIN
    RAISE NOTICE '[V25] sub_task.status CHECK 约束已加入 DEAD_LETTER';
END $$;
