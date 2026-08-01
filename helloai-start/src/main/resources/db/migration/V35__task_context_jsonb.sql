-- ============================================================
-- V35__task_context_jsonb.sql
-- 用途：Task Running Spec 体系（Phase A）—— task 表增加 context JSONB 列
-- 背景：
--   子任务间上下文传递当前通过 SubTaskExecutionService.loadDependencyContext()
--   将前置子任务的原始 LLM 产出直接注入下游 Prompt；原始输出含大量噪声，
--   多个依赖叠加后撑爆小上下文模型，影响下游 executor 正确性。
--   本迁移为 task 表增加 context JSONB 列，用于存储 Task Running Spec
--   （Baseline + ExecutionRecords + ContextSummary），以结构化、经过收口的
--   摘要替代原始产出注入。
-- 机制：
--   - task.context.runningSpec：结构化运行态文档
--     - baseline：Planner 拆解时写入的目标/约束/DAG 结构
--     - executionRecords[]：每条 executor 回填的结构化摘要
--     - contextSummary：系统自动编译的下游上下文
--   - 默认 '{}'：旧数据行为与现状完全一致
--   - Phase A 使用 JSONB（本迁移）；Phase B 将迁移到独立 task_running_spec 表
-- ============================================================

ALTER TABLE task
    ADD COLUMN IF NOT EXISTS context JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN task.context IS '任务扩展上下文（JSONB，V35 新增。runningSpec：结构化运行态文档——Baseline+ExecutionRecords+ContextSummary；Phase A JSONB 过渡，Phase B 迁移到独立表）';

-- 验证日志
DO $$
BEGIN
    RAISE NOTICE '[V35] task.context 列已就绪';
END $$;
