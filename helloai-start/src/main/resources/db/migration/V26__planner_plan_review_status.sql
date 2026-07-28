-- ============================================================
-- V26__planner_plan_review_status.sql
-- 用途：Planner 平台内拆解 —— 规划草案态与任务规划态
-- 背景：
--   Planner 角色（API_KEY_LLM 执行面）通过 LLM 结构化输出把 Task 自动
--   拆解为 SubTask 草案，草案需用户确认后才进入既有分发链。
--   本迁移重建两个 CHECK 约束，加入新状态值：
--   1. sub_task.status 加入 PENDING_PLAN_REVIEW（规划草案，待用户确认）；
--   2. task.status 加入 PLANNING（拆解进行中/草案待确认，防重复触发）。
-- 机制：
--   - Task: PENDING → PLANNING（触发拆解）→ IN_PROGRESS（确认转正）
--            / 回退 PENDING（拆解失败或用户拒绝草案）
--   - SubTask: 创建即 PENDING_PLAN_REVIEW（草案）
--       → PENDING（用户确认，进入既有分发链）
--       → CANCELLED（用户拒绝，保留审计）
--   - PENDING_PLAN_REVIEW 对 claim/assignNext/自动重派/补偿定时任务
--     全部不可见（它们只扫 PENDING/ASSIGNED 等状态）
-- 参考：doc/HelloAI_实现差距表.md Planner 拆解条目
-- ============================================================

ALTER TABLE sub_task
    DROP CONSTRAINT IF EXISTS chk_sub_task_status;

ALTER TABLE sub_task
    ADD CONSTRAINT chk_sub_task_status CHECK (
        status IN ('PENDING_PLAN_REVIEW', 'PENDING', 'ASSIGNED', 'IN_PROGRESS', 'PAUSED', 'REVIEW', 'DONE', 'REWORK', 'BLOCKED', 'CANCELLED', 'DEAD_LETTER')
    );

ALTER TABLE task
    DROP CONSTRAINT IF EXISTS chk_task_status;

ALTER TABLE task
    ADD CONSTRAINT chk_task_status CHECK (
        status IN ('PENDING', 'PLANNING', 'IN_PROGRESS', 'DONE', 'CANCELLED')
    );

-- 验证日志
DO $$
BEGIN
    RAISE NOTICE '[V26] sub_task.status 已加入 PENDING_PLAN_REVIEW, task.status 已加入 PLANNING';
END $$;
