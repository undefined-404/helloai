-- ============================================================
-- V27__add_sub_task_depends_on.sql
-- 用途：内循环依赖编排 —— 子任务级依赖（拆解 → ready 放行）
-- 背景：
--   平台内规划循环需要"带依赖的拆解"：Planner 拆解时由 LLM 输出
--   每个子任务的依赖序号，confirm 落库后转换为真实 sub_task id
--   写入本列；分发端（SubTaskDispatchService / SubTaskPendingOrphanTask）
--   在放行前检查 depends_on 中所有子任务均为 DONE 才允许分发。
-- 机制：
--   - JSONB 数组存依赖的 sub_task id（Long），如 [1234, 5678]
--   - 默认空数组 '[]'：旧数据/无依赖任务行为与现状完全一致
--   - 依赖只在同一 task 的子任务之间（跨 Task 依赖不支持）
--   - 落库前做拓扑排序环校验，成环整批拒绝，库中不会有环
--   - 数据量小，暂不加 GIN 索引
-- ============================================================

ALTER TABLE sub_task
    ADD COLUMN IF NOT EXISTS depends_on JSONB NOT NULL DEFAULT '[]'::jsonb;

COMMENT ON COLUMN sub_task.depends_on IS '依赖的子任务 id 数组（JSONB，同 Task 内；全部 DONE 后本任务才可分发；空数组=无依赖）';

-- 验证日志
DO $$
BEGIN
    RAISE NOTICE '[V27] sub_task.depends_on 列已就绪';
END $$;
